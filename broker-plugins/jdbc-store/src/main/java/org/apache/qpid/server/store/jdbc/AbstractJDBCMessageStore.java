/*
*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*
*/
package org.apache.qpid.server.store.jdbc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;

import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.message.EnqueueableMessage;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.plugin.MessageMetaDataType;
import org.apache.qpid.server.store.Event;
import org.apache.qpid.server.store.EventListener;
import org.apache.qpid.server.store.EventManager;
import org.apache.qpid.server.store.MessageDurability;
import org.apache.qpid.server.store.MessageEnqueueRecord;
import org.apache.qpid.server.store.MessageHandle;
import org.apache.qpid.server.store.MessageMetaDataTypeRegistry;
import org.apache.qpid.server.store.MessageStore;
import org.apache.qpid.server.store.StorableMessageMetaData;
import org.apache.qpid.server.store.StoreException;
import org.apache.qpid.server.store.StoredMessage;
import org.apache.qpid.server.store.Transaction;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.store.handler.DistributedTransactionHandler;
import org.apache.qpid.server.store.handler.MessageHandler;
import org.apache.qpid.server.store.handler.MessageInstanceHandler;
import org.apache.qpid.server.txn.Xid;

public abstract class AbstractJDBCMessageStore implements MessageStore
{
    private static final String DB_VERSION_TABLE_NAME_SUFFIX = "QPID_DB_VERSION";
    private static final String QUEUE_ENTRY_TABLE_NAME_SUFFIX = "QPID_QUEUE_ENTRIES";
    private static final String META_DATA_TABLE_NAME_SUFFIX = "QPID_MESSAGE_METADATA";
    private static final String MESSAGE_CONTENT_TABLE_NAME_SUFFIX = "QPID_MESSAGE_CONTENT";
    private static final String XID_TABLE_NAME_SUFFIX = "QPID_XIDS";
    private static final String XID_ACTIONS_TABLE_NAME_SUFFIX = "QPID_XID_ACTIONS";

    private static final int DB_VERSION = 8;

    private final AtomicLong _messageId = new AtomicLong(0);


    protected final EventManager _eventManager = new EventManager();
    private ConfiguredObject<?> _parent;
    private String _tablePrefix = "";

    protected abstract boolean isMessageStoreOpen();

    protected abstract void checkMessageStoreOpen();
    private ScheduledThreadPoolExecutor _executor;

    public AbstractJDBCMessageStore()
    {
    }

    protected void setMaximumMessageId()
    {

        try
        {
            Connection conn = newAutoCommitConnection();
            try
            {
                setMaxMessageId(conn, "SELECT max(message_id) FROM " + getMessageContentTableName(), 1);
                setMaxMessageId(conn, "SELECT max(message_id) FROM " + getMetaDataTableName(), 1);
                setMaxMessageId(conn, "SELECT queue_id, max(message_id) FROM " + getQueueEntryTableName()
                                      + " GROUP BY queue_id " , 2);
            }
            finally
            {
                conn.close();
            }
        }
        catch (SQLException e)
        {
            throw new StoreException("Failed to determine maximum ids", e);
        }

    }

    private void setMaxMessageId(final Connection conn, final String query, int col) throws SQLException
    {
        PreparedStatement statement =
                conn.prepareStatement(query);
        try
        {
            ResultSet rs = statement.executeQuery();
            try
            {
                while(rs.next())
                {
                    long maxMessageId = rs.getLong(col);
                    if(_messageId.get() < maxMessageId)
                    {
                        _messageId.set(maxMessageId);
                    }
                }

            }
            finally
            {
                rs.close();
            }
        }
        finally
        {
            statement.close();
        }
    }

    protected void upgrade(ConfiguredObject<?> parent) throws StoreException
    {
        Connection conn = null;
        try
        {
            conn = newAutoCommitConnection();
            if (tableExists(getDbVersionTableName(), conn))
            {
                upgradeIfNecessary(parent);
            }
        }
        catch (SQLException e)
        {
            throw new StoreException("Failed to upgrade database", e);
        }
        finally
        {
            JdbcUtils.closeConnection(conn, getLogger());
        }
    }

    private void upgradeIfNecessary(ConfiguredObject<?> parent) throws SQLException
    {
        try (Connection conn = newAutoCommitConnection())
        {

            try (PreparedStatement statement = conn.prepareStatement("SELECT version FROM " + getDbVersionTableName()))
            {
                try (ResultSet rs = statement.executeQuery())
                {
                    if (!rs.next())
                    {
                        throw new StoreException(getDbVersionTableName() + " does not contain the database version");
                    }
                    int version = rs.getInt(1);
                    switch (version)
                    {
                        case 6:
                            upgradeFromV6();
                        case 7:
                            upgradeFromV7();
                        case DB_VERSION:
                            return;
                        default:
                            throw new StoreException("Unknown database version: " + version);
                    }
                }
            }
        }

    }

    private void upgradeFromV7() throws SQLException
    {
        updateDbVersion(8);
    }

    private void upgradeFromV6() throws SQLException
    {
        updateDbVersion(7);
    }

    private void updateDbVersion(int newVersion) throws SQLException
    {
        try (Connection conn = newAutoCommitConnection())
        {
            try (PreparedStatement statement = conn.prepareStatement("UPDATE " + getDbVersionTableName()
                                                                     + " SET version = ?"))
            {
                statement.setInt(1, newVersion);
                statement.execute();
            }
        }
    }

    protected void initMessageStore(final ConfiguredObject<?> parent)
    {
        _parent = parent;
        _executor = new ScheduledThreadPoolExecutor(4, new ThreadFactory()
        {
            private final AtomicInteger _count = new AtomicInteger();
            @Override
            public Thread newThread(final Runnable r)
            {
                final Thread thread = Executors.defaultThreadFactory().newThread(r);
                thread.setName(parent.getName() + "-store-"+_count.incrementAndGet());
                return thread;
            }
        });
        _executor.prestartAllCoreThreads();

    }

    @Override
    public void closeMessageStore()
    {
        if(_executor != null)
        {
            _executor.shutdown();
        }

    }

    protected abstract Logger getLogger();

    protected abstract String getSqlBlobType();

    protected abstract String getSqlVarBinaryType(int size);

    protected abstract String getSqlBigIntType();

    protected void createOrOpenMessageStoreDatabase() throws StoreException
    {
        Connection conn = null;
        try
        {
            conn = newAutoCommitConnection();

            createVersionTable(conn);
            createQueueEntryTable(conn);
            createMetaDataTable(conn);
            createMessageContentTable(conn);
            createXidTable(conn);
            createXidActionTable(conn);
        }
        catch (SQLException e)
        {
            throw new StoreException("Failed to create message store tables", e);
        }
        finally
        {
            JdbcUtils.closeConnection(conn, getLogger());
        }
    }

    private void createVersionTable(final Connection conn) throws SQLException
    {
        if(!tableExists(getDbVersionTableName(), conn))
        {
            try (Statement stmt = conn.createStatement())
            {
                stmt.execute("CREATE TABLE " + getDbVersionTableName() + " ( version int not null )");
            }

            try (PreparedStatement pstmt = conn.prepareStatement("INSERT INTO " + getDbVersionTableName()
                                                                 + " ( version ) VALUES ( ? )"))
            {
                pstmt.setInt(1, DB_VERSION);
                pstmt.execute();
            }
        }
    }

    private void createQueueEntryTable(final Connection conn) throws SQLException
    {
        if(!tableExists(getQueueEntryTableName(), conn))
        {
            try (Statement stmt = conn.createStatement())
            {
                stmt.execute("CREATE TABLE " + getQueueEntryTableName()
                             + " ( queue_id varchar(36) not null, message_id "
                             + getSqlBigIntType() + " not null, PRIMARY KEY (queue_id, message_id) )");
            }
        }

    }

    private void createMetaDataTable(final Connection conn) throws SQLException
    {
        if(!tableExists(getMetaDataTableName(), conn))
        {
            try (Statement stmt = conn.createStatement())
            {
                stmt.execute("CREATE TABLE "
                             + getMetaDataTableName()
                             + " ( message_id "
                             + getSqlBigIntType()
                             + " not null, meta_data "
                             + getSqlBlobType()
                             + ", PRIMARY KEY ( message_id ) )");
            }
        }

    }

    private void createMessageContentTable(final Connection conn) throws SQLException
    {
        if(!tableExists(getMessageContentTableName(), conn))
        {
            try (Statement stmt = conn.createStatement())
            {
                stmt.execute("CREATE TABLE "
                             + getMessageContentTableName()
                             + " ( message_id "
                             + getSqlBigIntType()
                             + " not null, content "
                             + getSqlBlobType()
                             + ", PRIMARY KEY (message_id) )");
            }
        }

    }

    private void createXidTable(final Connection conn) throws SQLException
    {
        if(!tableExists(getXidTableName(), conn))
        {
            try (Statement stmt = conn.createStatement())
            {
                stmt.execute("CREATE TABLE "
                             + getXidTableName()
                             + " ( format " + getSqlBigIntType() + " not null,"
                             + " global_id "
                             + getSqlVarBinaryType(64)
                             + ", branch_id "
                             + getSqlVarBinaryType(64)
                             + " ,  PRIMARY KEY ( format, "
                             +
                             "global_id, branch_id ))");
            }
        }
    }

    private void createXidActionTable(final Connection conn) throws SQLException
    {
        if(!tableExists(getXidActionsTableName(), conn))
        {
            try (Statement stmt = conn.createStatement())
            {
                stmt.execute("CREATE TABLE "
                             + getXidActionsTableName()
                             + " ( format "
                             + getSqlBigIntType()
                             + " not null,"
                             + " global_id "
                             + getSqlVarBinaryType(64)
                             + " not null, branch_id "
                             + getSqlVarBinaryType(
                        64)
                             + " not null, "
                             +
                             "action_type char not null, queue_id varchar(36) not null, message_id "
                             + getSqlBigIntType()
                             + " not null"
                             +
                             ",  PRIMARY KEY ( "
                             +
                             "format, global_id, branch_id, action_type, queue_id, message_id))");
            }
        }
    }

    protected boolean tableExists(final String tableName, final Connection conn) throws SQLException
    {
        return JdbcUtils.tableExists(tableName, conn);
    }

    @Override
    public <T extends StorableMessageMetaData> MessageHandle<T> addMessage(T metaData)
    {
        checkMessageStoreOpen();

        return new StoredJDBCMessage<T>(getNextMessageId(), metaData);

    }

    @Override
    public long getNextMessageId()
    {
        return _messageId.incrementAndGet();
    }

    private void removeMessage(long messageId)
    {
        try
        {
            Connection conn = newConnection();
            try
            {
                PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + getMetaDataTableName()
                                                               + " WHERE message_id = ?");
                try
                {
                    stmt.setLong(1,messageId);
                    int results = stmt.executeUpdate();
                    stmt.close();

                    if (results == 0)
                    {
                        getLogger().debug("Message id {} not found (attempt to remove failed - probably application initiated rollback)", messageId);
                    }

                    getLogger().debug("Deleted metadata for message {}", messageId);

                    stmt = conn.prepareStatement("DELETE FROM " + getMessageContentTableName()
                                                 + " WHERE message_id = ?");
                    stmt.setLong(1, messageId);
                    results = stmt.executeUpdate();
                }
                finally
                {
                    stmt.close();
                }
                conn.commit();
            }
            catch(SQLException e)
            {
                try
                {
                    conn.rollback();
                }
                catch(SQLException t)
                {
                    // ignore - we are re-throwing underlying exception
                }

                throw e;

            }
            finally
            {
                conn.close();
            }
        }
        catch (SQLException e)
        {
            throw new StoreException("Error removing message with id " + messageId + " from database: " + e.getMessage(), e);
        }

    }

    /**
     * Convenience method to create a new Connection configured for TRANSACTION_READ_COMMITED
     * isolation and with auto-commit transactions enabled.
     */
    protected Connection newAutoCommitConnection() throws SQLException
    {
        final Connection connection = newConnection();
        try
        {
            connection.setAutoCommit(true);
        }
        catch (SQLException sqlEx)
        {

            try
            {
                connection.close();
            }
            finally
            {
                throw sqlEx;
            }
        }

        return connection;
    }

    /**
     * Convenience method to create a new Connection configured for TRANSACTION_READ_COMMITED
     * isolation and with auto-commit transactions disabled.
     */
    protected Connection newConnection() throws SQLException
    {
        final Connection connection = getConnection();
        try
        {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        catch (SQLException sqlEx)
        {
            try
            {
                connection.close();
            }
            finally
            {
                throw sqlEx;
            }
        }
        return connection;
    }

    public abstract Connection getConnection() throws SQLException;

    @Override
    public Transaction newTransaction()
    {
        checkMessageStoreOpen();

        return new JDBCTransaction();
    }

    private void enqueueMessage(ConnectionWrapper connWrapper, final TransactionLogResource queue, Long messageId) throws StoreException
    {
        Connection conn = connWrapper.getConnection();


        try
        {
            if (getLogger().isDebugEnabled())
            {
                getLogger().debug("Enqueuing message {} on queue {} with id {} [Connection {}]",
                                  messageId, queue.getName(), queue.getId(), conn);
            }

            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + getQueueEntryTableName()
                                                                + " (queue_id, message_id) values (?,?)"))
            {
                stmt.setString(1, queue.getId().toString());
                stmt.setLong(2, messageId);
                stmt.executeUpdate();
            }

        }
        catch (SQLException e)
        {
            getLogger().error("Failed to enqueue message {}", messageId, e);
            throw new StoreException("Error writing enqueued message with id " + messageId + " for queue " + queue.getName() + " with id " + queue.getId()
                                     + " to database", e);
        }

    }

    private void dequeueMessage(ConnectionWrapper connWrapper, final UUID queueId,
                                Long messageId) throws StoreException
    {

        Connection conn = connWrapper.getConnection();

        try
        {
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + getQueueEntryTableName()
                                                                + " WHERE queue_id = ? AND message_id =?"))
            {
                stmt.setString(1, queueId.toString());
                stmt.setLong(2, messageId);
                int results = stmt.executeUpdate();


                if (results != 1)
                {
                    throw new StoreException("Unable to find message with id " + messageId
                                             + " on queue with id " + queueId);
                }

                getLogger().debug("Dequeuing message {} on queue with id {}", messageId, queueId);

            }

        }
        catch (SQLException e)
        {
            getLogger().error("Failed to dequeue message {}", messageId, e);
            throw new StoreException("Error deleting enqueued message with id " + messageId + " for queue with id "
                                     + queueId + " from database", e);
        }

    }

    private void removeXid(ConnectionWrapper connWrapper, long format, byte[] globalId, byte[] branchId)
            throws StoreException
    {
        Connection conn = connWrapper.getConnection();


        try
        {
            PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + getXidTableName()
                                                           + " WHERE format = ? and global_id = ? and branch_id = ?");
            try
            {
                stmt.setLong(1,format);
                stmt.setBytes(2,globalId);
                stmt.setBytes(3,branchId);
                int results = stmt.executeUpdate();



                if(results != 1)
                {
                    throw new StoreException("Unable to find message with xid");
                }
            }
            finally
            {
                stmt.close();
            }

            stmt = conn.prepareStatement("DELETE FROM " + getXidActionsTableName()
                                         + " WHERE format = ? and global_id = ? and branch_id = ?");
            try
            {
                stmt.setLong(1,format);
                stmt.setBytes(2,globalId);
                stmt.setBytes(3,branchId);
                int results = stmt.executeUpdate();

            }
            finally
            {
                stmt.close();
            }

        }
        catch (SQLException e)
        {
            getLogger().error("Failed to remove xid", e);
            throw new StoreException("Error deleting enqueued message with xid", e);
        }

    }

    private List<Runnable> recordXid(ConnectionWrapper connWrapper, long format, byte[] globalId, byte[] branchId,
                                     Transaction.EnqueueRecord[] enqueues, Transaction.DequeueRecord[] dequeues) throws StoreException
    {
        Connection conn = connWrapper.getConnection();


        try
        {

            PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + getXidTableName()
                                                           + " ( format, global_id, branch_id ) values (?, ?, ?)");
            try
            {
                stmt.setLong(1,format);
                stmt.setBytes(2, globalId);
                stmt.setBytes(3, branchId);
                stmt.executeUpdate();
            }
            finally
            {
                stmt.close();
            }

            for(Transaction.EnqueueRecord enqueue : enqueues)
            {
                StoredMessage storedMessage = enqueue.getMessage().getStoredMessage();
                if(storedMessage instanceof StoredJDBCMessage)
                {
                    ((StoredJDBCMessage) storedMessage).store(conn);
                }
            }


            stmt = conn.prepareStatement("INSERT INTO " + getXidActionsTableName()
                                         + " ( format, global_id, branch_id, action_type, " +
                                         "queue_id, message_id ) values (?,?,?,?,?,?) ");

            try
            {
                stmt.setLong(1,format);
                stmt.setBytes(2, globalId);
                stmt.setBytes(3, branchId);

                if(enqueues != null)
                {
                    stmt.setString(4, "E");
                    for(Transaction.EnqueueRecord record : enqueues)
                    {
                        stmt.setString(5, record.getResource().getId().toString());
                        stmt.setLong(6, record.getMessage().getMessageNumber());
                        stmt.executeUpdate();
                    }
                }

                if(dequeues != null)
                {
                    stmt.setString(4, "D");
                    for(Transaction.DequeueRecord record : dequeues)
                    {
                        stmt.setString(5, record.getEnqueueRecord().getQueueId().toString());
                        stmt.setLong(6, record.getEnqueueRecord().getMessageNumber());
                        stmt.executeUpdate();
                    }
                }

            }
            finally
            {
                stmt.close();
            }
            return Collections.emptyList();
        }
        catch (SQLException e)
        {
            getLogger().error("Failed to record xid", e);
            throw new StoreException("Error writing xid ", e);
        }

    }

    public void onOpen(final ConfiguredObject<?> parent)
    {
    }

    protected void setTablePrefix(final String tablePrefix)
    {
        _tablePrefix = tablePrefix;
    }

    private String getDbVersionTableName()
    {
        return _tablePrefix + DB_VERSION_TABLE_NAME_SUFFIX;
    }

    private String getQueueEntryTableName()
    {
        return _tablePrefix + QUEUE_ENTRY_TABLE_NAME_SUFFIX;
    }

    private String getMetaDataTableName()
    {
        return _tablePrefix + META_DATA_TABLE_NAME_SUFFIX;
    }

    private String getMessageContentTableName()
    {
        return _tablePrefix + MESSAGE_CONTENT_TABLE_NAME_SUFFIX;
    }

    private String getXidTableName()
    {
        return _tablePrefix + XID_TABLE_NAME_SUFFIX;
    }

    private String getXidActionsTableName()
    {
        return _tablePrefix + XID_ACTIONS_TABLE_NAME_SUFFIX;
    }

    private static final class ConnectionWrapper
    {
        private final Connection _connection;

        public ConnectionWrapper(Connection conn)
        {
            _connection = conn;
        }

        public Connection getConnection()
        {
            return _connection;
        }
    }


    private void commitTran(ConnectionWrapper connWrapper) throws StoreException
    {
        try
        {
            Connection conn = connWrapper.getConnection();
            conn.commit();

            getLogger().debug("commit tran completed");

            conn.close();
        }
        catch (SQLException e)
        {
            throw new StoreException("Error commit tx", e);
        }
    }

    private <X> ListenableFuture<X> commitTranAsync(final ConnectionWrapper connWrapper, final X val) throws StoreException
    {
        final SettableFuture<X> future = SettableFuture.create();
        _executor.submit(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    commitTran(connWrapper);
                                    future.set(val);
                                }
                                catch (RuntimeException e)
                                {
                                    future.setException(e);
                                }
                            }
                        });
        return future;
    }

    private void abortTran(ConnectionWrapper connWrapper) throws StoreException
    {
        if (connWrapper == null)
        {
            throw new StoreException("Fatal internal error: transactional context is empty at abortTran");
        }

        getLogger().debug("abort tran called: {}", connWrapper.getConnection());

        try
        {
            Connection conn = connWrapper.getConnection();
            conn.rollback();
            conn.close();
        }
        catch (SQLException e)
        {
            throw new StoreException("Error aborting transaction: " + e.getMessage(), e);
        }

    }

    private void storeMetaData(Connection conn, long messageId, StorableMessageMetaData metaData)
            throws SQLException
    {
        getLogger().debug("Adding metadata for message {}", messageId);

        PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + getMetaDataTableName()
                                                       + "( message_id , meta_data ) values (?, ?)");
        try
        {
            stmt.setLong(1, messageId);

            final int bodySize = 1 + metaData.getStorableSize();
            byte[] underlying = new byte[bodySize];
            underlying[0] = (byte) metaData.getType().ordinal();
            QpidByteBuffer buf = QpidByteBuffer.wrap(underlying);
            buf.position(1);
            buf = buf.slice();

            metaData.writeToBuffer(buf);
            ByteArrayInputStream bis = new ByteArrayInputStream(underlying);
            try
            {
                stmt.setBinaryStream(2, bis, underlying.length);
                int result = stmt.executeUpdate();

                if (result == 0)
                {
                    throw new StoreException("Unable to add meta data for message " + messageId);
                }
            }
            finally
            {
                try
                {
                    bis.close();
                }
                catch (IOException e)
                {

                    throw new SQLException(e);
                }
            }

        }
        finally
        {
            stmt.close();
        }

    }


    private static class RecordImpl implements Transaction.EnqueueRecord, Transaction.DequeueRecord, TransactionLogResource, EnqueueableMessage
    {

        private final JDBCEnqueueRecord _record;
        private long _messageNumber;
        private UUID _queueId;

        public RecordImpl(UUID queueId, long messageNumber)
        {
            _messageNumber = messageNumber;
            _queueId = queueId;
            _record = new JDBCEnqueueRecord(queueId, messageNumber);
        }

        @Override
        public MessageEnqueueRecord getEnqueueRecord()
        {
            return _record;
        }

        @Override
        public TransactionLogResource getResource()
        {
            return this;
        }

        @Override
        public EnqueueableMessage getMessage()
        {
            return this;
        }

        @Override
        public long getMessageNumber()
        {
            return _messageNumber;
        }

        @Override
        public boolean isPersistent()
        {
            return true;
        }

        @Override
        public StoredMessage getStoredMessage()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName()
        {
            return _queueId.toString();
        }

        @Override
        public UUID getId()
        {
            return _queueId;
        }

        @Override
        public MessageDurability getMessageDurability()
        {
            return MessageDurability.DEFAULT;
        }
    }

    private StorableMessageMetaData getMetaData(long messageId) throws SQLException
    {

        Connection conn = newAutoCommitConnection();
        try
        {
            PreparedStatement stmt = conn.prepareStatement("SELECT meta_data FROM " + getMetaDataTableName()
                                                           + " WHERE message_id = ?");
            try
            {
                stmt.setLong(1,messageId);
                ResultSet rs = stmt.executeQuery();
                try
                {

                    if(rs.next())
                    {
                        byte[] dataAsBytes = getBlobAsBytes(rs, 1);
                        QpidByteBuffer buf = QpidByteBuffer.wrap(dataAsBytes);
                        buf.position(1);
                        buf = buf.slice();
                        MessageMetaDataType type = MessageMetaDataTypeRegistry.fromOrdinal(dataAsBytes[0]);
                        StorableMessageMetaData metaData = type.createMetaData(buf);
                        buf.dispose();
                        return metaData;
                    }
                    else
                    {
                        throw new StoreException("Meta data not found for message with id " + messageId);
                    }
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }
        }
        finally
        {
            conn.close();
        }
    }

    protected abstract byte[] getBlobAsBytes(ResultSet rs, int col) throws SQLException;

    private void addContent(final Connection conn, long messageId,
                            Collection<QpidByteBuffer> contentBody)
    {
        getLogger().debug("Adding content for message {}", messageId);

        PreparedStatement stmt = null;

        int size = 0;

        for(QpidByteBuffer buf : contentBody)
        {
            size += buf.remaining();
        }
        byte[] data = new byte[size];
        ByteBuffer dst = ByteBuffer.wrap(data);
        for(QpidByteBuffer buf : contentBody)
        {
            buf.copyTo(dst);
        }

        try
        {

            stmt = conn.prepareStatement("INSERT INTO " + getMessageContentTableName()
                                         + "( message_id, content ) values (?, ?)");
            stmt.setLong(1, messageId);
            stmt.setBinaryStream(2, new ByteArrayInputStream(data), data.length);
            stmt.executeUpdate();
        }
        catch (SQLException e)
        {
            JdbcUtils.closeConnection(conn, getLogger());
            throw new StoreException("Error adding content for message " + messageId + ": " + e.getMessage(), e);
        }
        finally
        {
            JdbcUtils.closePreparedStatement(stmt, getLogger());
        }
    }

    Collection<QpidByteBuffer> getAllContent(long messageId) throws StoreException
    {
        Connection conn = null;
        PreparedStatement stmt = null;

        getLogger().debug("Message Id: {} Getting content body", messageId);

        try
        {
            conn = newAutoCommitConnection();

            stmt = conn.prepareStatement("SELECT content FROM " + getMessageContentTableName()
                                         + " WHERE message_id = ?");
            stmt.setLong(1,messageId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next())
            {
                byte[] data = getBlobAsBytes(rs, 1);
                int offset = 0;
                int length = data.length;
                Collection<QpidByteBuffer> buffers = QpidByteBuffer.allocateDirectCollection(length);
                for(QpidByteBuffer buf : buffers)
                {
                    int bufSize = buf.remaining();
                    buf.put(data, offset, bufSize);
                    buf.flip();
                    offset+=bufSize;
                }
                return buffers;
            }
            else
            {
                throw new StoreException("Unable to find message with id " + messageId);
            }

        }
        catch (SQLException e)
        {
            throw new StoreException("Error retrieving content for message " + messageId + ": " + e.getMessage(), e);
        }
        finally
        {
            JdbcUtils.closePreparedStatement(stmt, getLogger());
            JdbcUtils.closeConnection(conn, getLogger());
        }
    }

    @Override
    public boolean isPersistent()
    {
        return true;
    }


    protected class JDBCTransaction implements Transaction
    {
        private final ConnectionWrapper _connWrapper;
        private int _storeSizeIncrease;
        private final List<Runnable> _preCommitActions = new ArrayList<>();
        private final List<Runnable> _postCommitActions = new ArrayList<>();

        protected JDBCTransaction()
        {
            try
            {
                _connWrapper = new ConnectionWrapper(newConnection());
            }
            catch (SQLException e)
            {
                throw new StoreException(e);
            }
        }

        @Override
        public MessageEnqueueRecord enqueueMessage(TransactionLogResource queue, EnqueueableMessage message)
        {
            checkMessageStoreOpen();

            final StoredMessage storedMessage = message.getStoredMessage();
            if(storedMessage instanceof StoredJDBCMessage)
            {
                _preCommitActions.add(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            ((StoredJDBCMessage) storedMessage).store(_connWrapper.getConnection());
                            _storeSizeIncrease += storedMessage.getContentSize();
                        }
                        catch (SQLException e)
                        {
                            throw new StoreException("Exception on enqueuing message into message store" + _messageId,
                                                     e);
                        }
                    }
                });
            }
            AbstractJDBCMessageStore.this.enqueueMessage(_connWrapper, queue, message.getMessageNumber());
            return new JDBCEnqueueRecord(queue.getId(), message.getMessageNumber());
        }

        @Override
        public void dequeueMessage(final MessageEnqueueRecord enqueueRecord)
        {
            checkMessageStoreOpen();

            AbstractJDBCMessageStore.this.dequeueMessage(_connWrapper,
                                                         enqueueRecord.getQueueId(),
                                                         enqueueRecord.getMessageNumber());
        }

        @Override
        public void commitTran()
        {
            checkMessageStoreOpen();
            doPreCommitActions();
            AbstractJDBCMessageStore.this.commitTran(_connWrapper);
            storedSizeChange(_storeSizeIncrease);
            doPostCommitActions();
        }

        @Override
        public <X> ListenableFuture<X> commitTranAsync(final X val)
        {
            checkMessageStoreOpen();
            doPreCommitActions();
            ListenableFuture<X> futureResult = AbstractJDBCMessageStore.this.commitTranAsync(_connWrapper, val);
            storedSizeChange(_storeSizeIncrease);
            doPostCommitActions();
            return futureResult;
        }

        private void doPreCommitActions()
        {
            for(Runnable action : _preCommitActions)
            {
                action.run();
            }
            _preCommitActions.clear();
        }

        private void doPostCommitActions()
        {
            // QPID-7447: prevent unnecessary allocation of empty iterator
            if (!_postCommitActions.isEmpty())
            {
                for (Runnable action : _postCommitActions)
                {
                    action.run();
                }
                _postCommitActions.clear();
            }
        }

        @Override
        public void abortTran()
        {
            checkMessageStoreOpen();
            _preCommitActions.clear();
            AbstractJDBCMessageStore.this.abortTran(_connWrapper);
        }

        @Override
        public void removeXid(final StoredXidRecord record)
        {
            checkMessageStoreOpen();

            AbstractJDBCMessageStore.this.removeXid(_connWrapper,
                                                    record.getFormat(),
                                                    record.getGlobalId(),
                                                    record.getBranchId());
        }

        @Override
        public StoredXidRecord recordXid(final long format,
                                         final byte[] globalId,
                                         final byte[] branchId,
                                         EnqueueRecord[] enqueues,
                                         DequeueRecord[] dequeues)
        {
            checkMessageStoreOpen();

            _postCommitActions.addAll(AbstractJDBCMessageStore.this.recordXid(_connWrapper, format, globalId, branchId, enqueues, dequeues));
            return new JDBCStoredXidRecord(format, globalId, branchId);
        }


    }

    private static class JDBCStoredXidRecord implements Transaction.StoredXidRecord
    {
        private final long _format;
        private final byte[] _globalId;
        private final byte[] _branchId;

        public JDBCStoredXidRecord(final long format, final byte[] globalId, final byte[] branchId)
        {
            _format = format;
            _globalId = globalId;
            _branchId = branchId;
        }

        @Override
        public long getFormat()
        {
            return _format;
        }

        @Override
        public byte[] getGlobalId()
        {
            return _globalId;
        }

        @Override
        public byte[] getBranchId()
        {
            return _branchId;
        }


        @Override
        public boolean equals(final Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (o == null || getClass() != o.getClass())
            {
                return false;
            }

            final JDBCStoredXidRecord that = (JDBCStoredXidRecord) o;

            return _format == that._format
                   && Arrays.equals(_globalId, that._globalId)
                   && Arrays.equals(_branchId, that._branchId);

        }

        @Override
        public int hashCode()
        {
            int result = (int) (_format ^ (_format >>> 32));
            result = 31 * result + Arrays.hashCode(_globalId);
            result = 31 * result + Arrays.hashCode(_branchId);
            return result;
        }
    }

    static interface MessageDataRef<T extends StorableMessageMetaData>
    {
        T getMetaData();
        Collection<QpidByteBuffer> getData();
        void setData(Collection<QpidByteBuffer> data);
        boolean isHardRef();
    }

    private static final class MessageDataHardRef<T extends StorableMessageMetaData> implements MessageDataRef<T>
    {
        private final T _metaData;
        private volatile Collection<QpidByteBuffer> _data;

        private MessageDataHardRef(final T metaData)
        {
            _metaData = metaData;
        }

        @Override
        public T getMetaData()
        {
            return _metaData;
        }

        @Override
        public Collection<QpidByteBuffer> getData()
        {
            return _data;
        }

        @Override
        public void setData(final Collection<QpidByteBuffer> data)
        {
            _data = data;
        }

        @Override
        public boolean isHardRef()
        {
            return true;
        }
    }

    private static final class MessageDataSoftRef<T extends StorableMessageMetaData> implements MessageDataRef<T>
    {

        private T _metaData;
        private volatile Collection<QpidByteBuffer> _data;

        private MessageDataSoftRef(final T metaData, Collection<QpidByteBuffer> data)
        {
            _metaData = metaData;
            _data = data;
        }

        @Override
        public T getMetaData()
        {
            return _metaData;
        }

        @Override
        public Collection<QpidByteBuffer> getData()
        {
            return _data;
        }

        @Override
        public void setData(final Collection<QpidByteBuffer> data)
        {
            _data = data;
        }

        public void clear()
        {
            if(_metaData != null)
            {
                _metaData.clearEncodedForm();
                _metaData = null;
            }
            if(_data != null)
            {
                for(QpidByteBuffer buf : _data)
                {
                    buf.dispose();
                }
            }
            _data = null;
        }

        @Override
        public boolean isHardRef()
        {
            return false;
        }
    }

    private class StoredJDBCMessage<T extends StorableMessageMetaData> implements StoredMessage<T>, MessageHandle<T>
    {

        private final long _messageId;
        private final int _contentSize;

        private MessageDataRef<T> _messageDataRef;


        StoredJDBCMessage(long messageId, T metaData)
        {
            this(messageId, metaData, false);
        }


        StoredJDBCMessage(long messageId,
                          T metaData, boolean isRecovered)
        {
            _messageId = messageId;

            if(!isRecovered)
            {
                _messageDataRef = new MessageDataHardRef<>(metaData);
            }
            else
            {
                _messageDataRef = new MessageDataSoftRef<>(metaData, null);
            }
            _contentSize = metaData.getContentSize();
        }


        @Override
        public synchronized T getMetaData()
        {
            if (_messageDataRef == null)
            {
                return null;
            }
            else
            {
                T metaData = _messageDataRef.getMetaData();

                if (metaData == null)
                {
                    checkMessageStoreOpen();
                    try
                    {
                        metaData = (T) AbstractJDBCMessageStore.this.getMetaData(_messageId);
                        _messageDataRef = new MessageDataSoftRef<>(metaData, _messageDataRef.getData());
                    }
                    catch (SQLException e)
                    {
                        throw new StoreException("Failed to get metadata for message id: " + _messageId, e);
                    }
                }
                return metaData;
            }
        }

        @Override
        public long getMessageNumber()
        {
            return _messageId;
        }

        @Override
        public synchronized void addContent(QpidByteBuffer src)
        {
            src = src.slice();
            Collection<QpidByteBuffer> data = _messageDataRef.getData();
            if(data == null)
            {
                _messageDataRef.setData(Collections.singleton(src));
            }
            else
            {
                List<QpidByteBuffer> newCollection = new ArrayList<>(data.size()+1);
                newCollection.addAll(data);
                newCollection.add(src);
                _messageDataRef.setData(Collections.unmodifiableCollection(newCollection));
            }

        }

        @Override
        public StoredMessage<T> allContentAdded()
        {
            return this;
        }

        /**
         * returns QBBs containing the content. The caller must not dispose of them because we keep a reference in _messageDataRef.
         */
        private Collection<QpidByteBuffer> getContentAsByteBuffer()
        {
            Collection<QpidByteBuffer> data = _messageDataRef == null ? Collections.<QpidByteBuffer>emptyList() : _messageDataRef.getData();
            if(data == null)
            {
                if(stored())
                {
                    checkMessageStoreOpen();
                    data = AbstractJDBCMessageStore.this.getAllContent(_messageId);
                    _messageDataRef.setData(data);
                }
                else
                {
                    data = Collections.emptyList();
                }
            }
            return data;
        }

        @Override
        public synchronized Collection<QpidByteBuffer> getContent(int offset, int length)
        {
            Collection<QpidByteBuffer> bufs = getContentAsByteBuffer();
            Collection<QpidByteBuffer> content = new ArrayList<>(bufs.size());

            int pos = 0;
            for (QpidByteBuffer buf : bufs)
            {
                if (length > 0)
                {
                    int bufRemaining = buf.remaining();
                    if (pos + bufRemaining <= offset)
                    {
                        pos += bufRemaining;
                    }
                    else if (pos >= offset)
                    {
                        buf = buf.duplicate();
                        if (bufRemaining <= length)
                        {
                            length -= bufRemaining;
                        }
                        else
                        {
                            buf.limit(length);
                            length = 0;
                        }
                        content.add(buf);
                        pos += buf.remaining();

                    }
                    else
                    {
                        int offsetInBuf = offset - pos;
                        int limit = length < bufRemaining - offsetInBuf ? length : bufRemaining - offsetInBuf;
                        final QpidByteBuffer bufView = buf.view(offsetInBuf, limit);
                        content.add(bufView);
                        length -= limit;
                        pos+=limit+offsetInBuf;
                    }
                }

            }
            return content;
        }

        @Override
        public int getContentSize()
        {
            return _contentSize;
        }

        synchronized void store(final Connection conn) throws SQLException
        {
            if (!stored())
            {

                AbstractJDBCMessageStore.this.storeMetaData(conn, _messageId, _messageDataRef.getMetaData());
                AbstractJDBCMessageStore.this.addContent(conn, _messageId,
                                                         _messageDataRef.getData() == null
                                                                ? Collections.<QpidByteBuffer>emptySet()
                                                                : _messageDataRef.getData());

                getLogger().debug("Storing message {} to store", _messageId);

                MessageDataRef<T> hardRef = _messageDataRef;
                MessageDataSoftRef<T> messageDataSoftRef;

                messageDataSoftRef = new MessageDataSoftRef<>(hardRef.getMetaData(), hardRef.getData());

                _messageDataRef = messageDataSoftRef;


            }
        }

        synchronized ListenableFuture<Void> flushToStore()
        {
            if (_messageDataRef != null)
            {
                if(!stored())
                {
                    try (Connection conn = newConnection())
                    {
                        store(conn);
                        conn.commit();
                        storedSizeChange(getContentSize());
                    }
                    catch (SQLException e)
                    {
                        throw new StoreException("Failed to flow to disk", e);
                    }
                }

            }
            return Futures.immediateFuture(null);
        }

        @Override
        public synchronized void remove()
        {
            getLogger().debug("REMOVE called on message: {}", _messageId);

            checkMessageStoreOpen();
            if(stored())
            {
                AbstractJDBCMessageStore.this.removeMessage(_messageId);
                storedSizeChange(-getContentSize());
            }

            final T metaData;
            if ((metaData = _messageDataRef.getMetaData()) != null)
            {
                metaData.dispose();
            }

            Collection<QpidByteBuffer> data = _messageDataRef.getData();
            if(data != null)
            {
                _messageDataRef.setData(null);
                for(QpidByteBuffer buf : data)
                {
                    buf.dispose();
                }
            }
            _messageDataRef = null;
        }

        @Override
        public synchronized boolean isInMemory()
        {
            return _messageDataRef != null && (_messageDataRef.isHardRef() || _messageDataRef.getData() != null);
        }

        private boolean stored()
        {
            return _messageDataRef != null && !_messageDataRef.isHardRef();
        }

        @Override
        public synchronized boolean flowToDisk()
        {

            flushToStore();
            if(_messageDataRef != null && !_messageDataRef.isHardRef())
            {
                ((MessageDataSoftRef)_messageDataRef).clear();
            }
            return true;
        }

        @Override
        public String toString()
        {
            return this.getClass() + "[messageId=" + _messageId + "]";
        }
    }

    @Override
    public void addEventListener(EventListener eventListener, Event... events)
    {
        _eventManager.addEventListener(eventListener, events);
    }

    @Override
    public MessageStoreReader newMessageStoreReader()
    {
        return new JDBCMessageStoreReader();
    }

    private class JDBCMessageStoreReader implements MessageStoreReader
    {

        @Override
        public StoredMessage<?> getMessage(long messageId) throws StoreException
        {
            checkMessageStoreOpen();

            Connection conn = null;
            StoredJDBCMessage message;
            try
            {
                conn = newAutoCommitConnection();
                try (PreparedStatement stmt = conn.prepareStatement("SELECT message_id, meta_data FROM " + getMetaDataTableName()
                                                                    + " WHERE message_id = ?"))
                {
                    stmt.setLong(1, messageId);
                    try (ResultSet rs = stmt.executeQuery())
                    {
                        if (rs.next())
                        {
                            byte[] dataAsBytes = getBlobAsBytes(rs, 2);
                            QpidByteBuffer buf = QpidByteBuffer.wrap(dataAsBytes);
                            buf.position(1);
                            buf = buf.slice();
                            MessageMetaDataType<?> type = MessageMetaDataTypeRegistry.fromOrdinal(dataAsBytes[0]);
                            StorableMessageMetaData metaData = type.createMetaData(buf);
                            buf.dispose();
                            message = new StoredJDBCMessage(messageId, metaData, true);

                        }
                        else
                        {
                            message = null;
                        }
                    }
                }
                return message;
            }
            catch (SQLException e)
            {
                throw new StoreException("Error encountered when visiting messages", e);
            }
            finally
            {
                JdbcUtils.closeConnection(conn, getLogger());
            }
        }

        @Override
        public void close()
        {

        }


        @Override
        public void visitMessages(MessageHandler handler) throws StoreException
        {
            checkMessageStoreOpen();

            Connection conn = null;
            try
            {
                conn = newAutoCommitConnection();
                Statement stmt = conn.createStatement();
                try
                {
                    ResultSet rs = stmt.executeQuery("SELECT message_id, meta_data FROM " + getMetaDataTableName());
                    try
                    {
                        while (rs.next())
                        {
                            long messageId = rs.getLong(1);
                            byte[] dataAsBytes = getBlobAsBytes(rs, 2);
                            QpidByteBuffer buf = QpidByteBuffer.wrap(dataAsBytes);
                            buf.position(1);
                            buf = buf.slice();
                            MessageMetaDataType<?> type = MessageMetaDataTypeRegistry.fromOrdinal(((int)dataAsBytes[0]) &0xff);
                            StorableMessageMetaData metaData = type.createMetaData(buf);
                            buf.dispose();
                            StoredJDBCMessage message = new StoredJDBCMessage(messageId, metaData, true);
                            if (!handler.handle(message))
                            {
                                break;
                            }
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    stmt.close();
                }
            }
            catch (SQLException e)
            {
                throw new StoreException("Error encountered when visiting messages", e);
            }
            finally
            {
                JdbcUtils.closeConnection(conn, getLogger());
            }
        }

        @Override
        public void visitMessageInstances(TransactionLogResource queue, MessageInstanceHandler handler)
                throws StoreException
        {
            checkMessageStoreOpen();

            Connection conn = null;
            try
            {
                conn = newAutoCommitConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT queue_id, message_id FROM " + getQueueEntryTableName()
                                                               + " WHERE queue_id = ? ORDER BY queue_id, message_id");
                try
                {
                    stmt.setString(1, queue.getId().toString());
                    ResultSet rs = stmt.executeQuery();
                    try
                    {
                        while (rs.next())
                        {
                            String id = rs.getString(1);
                            long messageId = rs.getLong(2);
                            if (!handler.handle(new JDBCEnqueueRecord(UUID.fromString(id), messageId)))
                            {
                                break;
                            }
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    stmt.close();
                }
            }
            catch (SQLException e)
            {
                throw new StoreException("Error encountered when visiting message instances", e);
            }
            finally
            {
                JdbcUtils.closeConnection(conn, getLogger());
            }

        }

        @Override
        public void visitMessageInstances(MessageInstanceHandler handler) throws StoreException
        {
            checkMessageStoreOpen();

            Connection conn = null;
            try
            {
                conn = newAutoCommitConnection();
                Statement stmt = conn.createStatement();
                try
                {
                    ResultSet rs = stmt.executeQuery("SELECT queue_id, message_id FROM " + getQueueEntryTableName()
                                                     + " ORDER BY queue_id, message_id");
                    try
                    {
                        while (rs.next())
                        {
                            String id = rs.getString(1);
                            long messageId = rs.getLong(2);
                            if (!handler.handle(new JDBCEnqueueRecord(UUID.fromString(id), messageId)))
                            {
                                break;
                            }
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    stmt.close();
                }
            }
            catch (SQLException e)
            {
                throw new StoreException("Error encountered when visiting message instances", e);
            }
            finally
            {
                JdbcUtils.closeConnection(conn, getLogger());
            }
        }

        @Override
        public void visitDistributedTransactions(DistributedTransactionHandler handler) throws StoreException
        {
            checkMessageStoreOpen();

            Connection conn = null;
            try
            {
                conn = newAutoCommitConnection();
                List<Xid> xids = new ArrayList<Xid>();

                Statement stmt = conn.createStatement();
                try
                {
                    ResultSet rs = stmt.executeQuery("SELECT format, global_id, branch_id FROM " + getXidTableName());
                    try
                    {
                        while (rs.next())
                        {

                            long format = rs.getLong(1);
                            byte[] globalId = rs.getBytes(2);
                            byte[] branchId = rs.getBytes(3);
                            xids.add(new Xid(format, globalId, branchId));
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    stmt.close();
                }


                for (Xid xid : xids)
                {
                    List<RecordImpl> enqueues = new ArrayList<>();
                    List<RecordImpl> dequeues = new ArrayList<>();

                    PreparedStatement pstmt = conn.prepareStatement("SELECT action_type, queue_id, message_id FROM " + getXidActionsTableName()
                                                                    +
                                                                    " WHERE format = ? and global_id = ? and branch_id = ?");

                    try
                    {
                        pstmt.setLong(1, xid.getFormat());
                        pstmt.setBytes(2, xid.getGlobalId());
                        pstmt.setBytes(3, xid.getBranchId());

                        ResultSet rs = pstmt.executeQuery();
                        try
                        {
                            while (rs.next())
                            {

                                String actionType = rs.getString(1);
                                UUID queueId = UUID.fromString(rs.getString(2));
                                long messageId = rs.getLong(3);

                                RecordImpl record = new RecordImpl(queueId, messageId);
                                List<RecordImpl> records = "E".equals(actionType) ? enqueues : dequeues;
                                records.add(record);
                            }
                        }
                        finally
                        {
                            rs.close();
                        }
                    }
                    finally
                    {
                        pstmt.close();
                    }

                    if (!handler.handle(new JDBCStoredXidRecord(xid.getFormat(), xid.getGlobalId(), xid.getBranchId()),
                                        enqueues.toArray(new RecordImpl[enqueues.size()]),
                                        dequeues.toArray(new RecordImpl[dequeues.size()])))
                    {
                        break;
                    }
                }

            }
            catch (SQLException e)
            {
                throw new StoreException("Error encountered when visiting distributed transactions", e);

            }
            finally
            {
                JdbcUtils.closeConnection(conn, getLogger());
            }
        }
    }

    protected abstract void storedSizeChange(int storeSizeIncrease);

    @Override
    public void onDelete(ConfiguredObject<?> parent)
    {
        // TODO should probably check we are closed
        try
        {
            Connection conn = newAutoCommitConnection();
            try
            {

                for (String tableName : getTableNames())
                {
                    Statement stmt = conn.createStatement();
                    try
                    {
                        stmt.execute("DROP TABLE " +  tableName);
                    }
                    catch(SQLException e)
                    {
                        getLogger().warn("Failed to drop table '{}'", tableName, e);
                    }
                    finally
                    {
                        stmt.close();
                    }
                }
            }
            finally
            {
                conn.close();
            }
        }
        catch(SQLException e)
        {
            getLogger().error("Exception while deleting store tables", e);
        }
    }

    public List<String> getTableNames()
    {
        return Arrays.asList(getDbVersionTableName(),
                             getMetaDataTableName(),
                             getMessageContentTableName(),
                             getQueueEntryTableName(),
                             getXidTableName(),
                             getXidActionsTableName());
    }


    private static class JDBCEnqueueRecord implements MessageEnqueueRecord
    {
        private final UUID _queueId;
        private final long _messageNumber;

        public JDBCEnqueueRecord(final UUID queueId,
                                 final long messageNumber)
        {
            _queueId = queueId;
            _messageNumber = messageNumber;
        }

        public UUID getQueueId()
        {
            return _queueId;
        }

        public long getMessageNumber()
        {
            return _messageNumber;
        }
    }
}