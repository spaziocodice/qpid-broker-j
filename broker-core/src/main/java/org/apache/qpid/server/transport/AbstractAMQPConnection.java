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
package org.apache.qpid.server.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.security.auth.Subject;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.configuration.updater.TaskExecutor;
import org.apache.qpid.server.connection.ConnectionPrincipal;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.logging.LogSubject;
import org.apache.qpid.server.logging.messages.ConnectionMessages;
import org.apache.qpid.server.logging.subjects.ConnectionLogSubject;
import org.apache.qpid.server.model.AbstractConfiguredObject;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Protocol;
import org.apache.qpid.server.model.Session;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.StateTransition;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.model.adapter.SessionAdapter;
import org.apache.qpid.server.model.port.AmqpPort;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;
import org.apache.qpid.server.stats.StatisticsCounter;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.transport.network.AggregateTicker;
import org.apache.qpid.transport.network.NetworkConnection;
import org.apache.qpid.transport.network.Ticker;

public abstract class AbstractAMQPConnection<C extends AbstractAMQPConnection<C>>
        extends AbstractConfiguredObject<C>
        implements ProtocolEngine, AMQPConnection<C>

{
    private static final Logger _logger = LoggerFactory.getLogger(AbstractAMQPConnection.class);

    private final Broker<?> _broker;
    private final ServerNetworkConnection _network;
    private final AmqpPort<?> _port;
    private final Transport _transport;
    private final Protocol _protocol;
    private final long _connectionId;
    private final AggregateTicker _aggregateTicker;
    private final Subject _subject = new Subject();
    private final List<Action<? super C>> _connectionCloseTaskList =
            new CopyOnWriteArrayList<>();

    private final LogSubject _logSubject;
    private final AtomicReference<Thread> _messageAssignmentAllowedThread = new AtomicReference<>();
    private final AtomicBoolean _messageAssignmentSuspended = new AtomicBoolean();
    private String _clientProduct;
    private String _clientVersion;
    private String _remoteProcessPid;

    private String _clientId;
    private volatile boolean _stopped;
    private final StatisticsCounter _messagesDelivered, _dataDelivered, _messagesReceived, _dataReceived;
    private final SettableFuture<Void> _transportClosedFuture = SettableFuture.create();
    private final SettableFuture<Void> _modelClosedFuture = SettableFuture.create();
    private final AtomicBoolean _modelClosing = new AtomicBoolean();
    private volatile VirtualHost<?> _virtualHost;
    private volatile long _lastReadTime;
    private volatile long _lastWriteTime;
    private volatile AccessControlContext _accessControllerContext;
    private volatile Thread _ioThread;

    private volatile boolean _messageAuthorizationRequired;

    private final AtomicLong _maxMessageSize = new AtomicLong(Long.MAX_VALUE);
    private volatile int _messageCompressionThreshold;

    public AbstractAMQPConnection(Broker<?> broker,
                                  ServerNetworkConnection network,
                                  AmqpPort<?> port,
                                  Transport transport,
                                  Protocol protocol,
                                  long connectionId,
                                  AggregateTicker aggregateTicker)
    {
        super(parentsMap(port),createAttributes(connectionId, network));

        _broker = broker;
        _network = network;
        _port = port;
        _transport = transport;
        _protocol = protocol;
        _connectionId = connectionId;
        _aggregateTicker = aggregateTicker;
        _subject.getPrincipals().add(new ConnectionPrincipal(this));

        updateAccessControllerContext();

        _messagesDelivered = new StatisticsCounter("messages-delivered-" + getConnectionId());
        _dataDelivered = new StatisticsCounter("data-delivered-" + getConnectionId());
        _messagesReceived = new StatisticsCounter("messages-received-" + getConnectionId());
        _dataReceived = new StatisticsCounter("data-received-" + getConnectionId());

        _transportClosedFuture.addListener(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            deleted();
                            setState(State.DELETED);
                            logConnectionClose();
                        }
                        finally
                        {
                            _modelClosedFuture.set(null);
                        }
                    }
                }, getTaskExecutor());

        setState(State.ACTIVE);
        _logSubject = new ConnectionLogSubject(this);
    }

    private static Map<String, Object> createAttributes(long connectionId, NetworkConnection network)
    {
        Map<String,Object> attributes = new HashMap<>();
        attributes.put(NAME, "[" + connectionId + "] " + String.valueOf(network.getRemoteAddress()).replaceAll("/", ""));
        attributes.put(DURABLE, false);
        return attributes;
    }

    @Override
    protected void onOpen()
    {
        super.onOpen();
        long maxAuthDelay = _port.getContextValue(Long.class, Port.CONNECTION_MAXIMUM_AUTHENTICATION_DELAY);
        SlowConnectionOpenTicker slowConnectionOpenTicker = new SlowConnectionOpenTicker(maxAuthDelay);
        _network.addSchedulingDelayNotificationListeners(slowConnectionOpenTicker);
        _aggregateTicker.addTicker(slowConnectionOpenTicker);
        _lastReadTime = _lastWriteTime = getCreatedTime();

        logConnectionOpen();
    }

    public Broker<?> getBroker()
    {
        return _broker;
    }

    public final ServerNetworkConnection getNetwork()
    {
        return _network;
    }

    @Override
    public final AmqpPort<?> getPort()
    {
        return _port;
    }

    @Override
    public final Transport getTransport()
    {
        return _transport;
    }

    @Override
    public String getTransportInfo()
    {
        return _network.getTransportInfo();
    }

    @Override
    public Protocol getProtocol()
    {
        return _protocol;
    }

    @Override
    public final AggregateTicker getAggregateTicker()
    {
        return _aggregateTicker;
    }

    @Override
    public final long getLastIoTime()
    {
        return Math.max(getLastReadTime(), getLastWriteTime());
    }

    @Override
    public final long getLastReadTime()
    {
        return _lastReadTime;
    }

    public final void updateLastReadTime()
    {
        _lastReadTime = System.currentTimeMillis();
    }

    @Override
    public final long getLastWriteTime()
    {
        return _lastWriteTime;
    }

    public final void updateLastWriteTime()
    {
        _lastWriteTime = System.currentTimeMillis();
    }

    @Override
    public final long getConnectionId()
    {
        return _connectionId;
    }

    private StatisticsCounter getMessageDeliveryStatistics()
    {
        return _messagesDelivered;
    }

    @Override
    public String getRemoteAddressString()
    {
        return String.valueOf(_network.getRemoteAddress());
    }

    @Override
    public final void stopConnection()
    {
        _stopped = true;
    }

    @Override
    public boolean isConnectionStopped()
    {
        return _stopped;
    }

    @Override
    public final String getVirtualHostName()
    {
        return getVirtualHost() == null ? null : getVirtualHost().getName();
    }

    @Override
    public String getClientVersion()
    {
        return _clientVersion;
    }

    @Override
    public String getRemoteProcessPid()
    {
        return _remoteProcessPid;
    }

    @Override
    public void pushScheduler(final NetworkConnectionScheduler networkConnectionScheduler)
    {
        if(_network instanceof NonBlockingConnection)
        {
            ((NonBlockingConnection) _network).pushScheduler(networkConnectionScheduler);
        }
    }

    @Override
    public NetworkConnectionScheduler popScheduler()
    {
        if(_network instanceof NonBlockingConnection)
        {
            return ((NonBlockingConnection) _network).popScheduler();
        }
        return null;
    }

    @Override
    public String getClientProduct()
    {
        return _clientProduct;
    }

    protected void updateMaxMessageSize()
    {
        _maxMessageSize.set(Math.min(getMaxMessageSize(getPort()), getMaxMessageSize(getVirtualHost())));
    }

    private long getMaxMessageSize(final ConfiguredObject<?> object)
    {
        long maxMessageSize;
        try
        {
            maxMessageSize = object.getContextValue(Integer.class, MAX_MESSAGE_SIZE);
        }
        catch (NullPointerException | IllegalArgumentException e)
        {
            _logger.warn("Context variable {} has invalid value and cannot be used to restrict maximum message size",
                         MAX_MESSAGE_SIZE,
                         e);
            maxMessageSize = Long.MAX_VALUE;
        }
        return maxMessageSize > 0 ? maxMessageSize : Long.MAX_VALUE;
    }

    public long getMaxMessageSize()
    {
        return _maxMessageSize.get();
    }

    @Override
    public void addDeleteTask(final Action<? super C> task)
    {
        _connectionCloseTaskList.add(task);
    }

    @Override
    public void removeDeleteTask(final Action<? super C> task)
    {
        _connectionCloseTaskList.remove(task);
    }


    public void performDeleteTasks()
    {
        if(runningAsSubject())
        {
            for (Action<? super C> task : _connectionCloseTaskList)
            {
                task.performAction((C)this);
            }
        }
        else
        {
            runAsSubject(new PrivilegedAction<Object>()
            {
                @Override
                public Object run()
                {
                    performDeleteTasks();
                    return null;
                }
            });
        }
    }

    @Override
    public String getClientId()
    {
        return _clientId;
    }

    private StatisticsCounter getDataReceiptStatistics()
    {
        return _dataReceived;
    }

    private StatisticsCounter getDataDeliveryStatistics()
    {
        return _dataDelivered;
    }

    @Override
    public final SocketAddress getRemoteSocketAddress()
    {
        return _network.getRemoteAddress();
    }

    @Override
    public void registerMessageDelivered(long messageSize)
    {
        _messagesDelivered.registerEvent(1L);
        _dataDelivered.registerEvent(messageSize);
        getVirtualHost().registerMessageDelivered(messageSize);
    }

    @Override
    public void registerMessageReceived(long messageSize, long timestamp)
    {
        _messagesReceived.registerEvent(1L, timestamp);
        _dataReceived.registerEvent(messageSize, timestamp);
        getVirtualHost().registerMessageReceived(messageSize, timestamp);
    }

    @Override
    public final void resetStatistics()
    {
        _messagesDelivered.reset();
        _dataDelivered.reset();
        _messagesReceived.reset();
        _dataReceived.reset();
    }

    private StatisticsCounter getMessageReceiptStatistics()
    {
        return _messagesReceived;
    }

    public void setClientProduct(final String clientProduct)
    {
        _clientProduct = clientProduct;
    }

    public void setClientVersion(final String clientVersion)
    {
        _clientVersion = clientVersion;
    }

    public void setRemoteProcessPid(final String remoteProcessPid)
    {
        _remoteProcessPid = remoteProcessPid;
    }

    public void setClientId(final String clientId)
    {
        _clientId = clientId;
    }

    @Override
    public boolean isMessageAssignmentSuspended()
    {
        Thread currentThread = Thread.currentThread();
        if (_messageAssignmentAllowedThread.get() == currentThread && currentThread == _ioThread)
        {
            return false;
        }
        return _messageAssignmentSuspended.get();
    }

    @Override
    public void setMessageAssignmentSuspended(final boolean messageAssignmentSuspended, final boolean notifyConsumers)
    {
        _messageAssignmentSuspended.set(messageAssignmentSuspended);
        if(notifyConsumers)
        {
            for (AMQSessionModel<?> session : getSessionModels())
            {
                if (messageAssignmentSuspended)
                {
                    session.ensureConsumersNoticedStateChange();
                }
                else
                {
                    session.notifyConsumerTargetCurrentStates();
                }
            }
        }
    }

    @Override
    public void alwaysAllowMessageAssignmentInThisThreadIfItIsIOThread(boolean allowed)
    {
        if (allowed)
        {
            _messageAssignmentAllowedThread.set(Thread.currentThread());
        }
        else
        {
            _messageAssignmentAllowedThread.set(null);
        }
    }

    @Override
    public void setIOThread(final Thread ioThread)
    {
        _ioThread = ioThread;
    }

    @Override
    public boolean isIOThread()
    {
        return Thread.currentThread() == _ioThread;
    }

    protected <T> T runAsSubject(PrivilegedAction<T> action)
    {
        return Subject.doAs(_subject, action);
    }

    private boolean runningAsSubject()
    {
        return _subject.equals(Subject.getSubject(AccessController.getContext()));
    }

    @Override
    public Subject getSubject()
    {
        return _subject;
    }

    public void sessionAdded(final AMQSessionModel<?> session)
    {
        SessionAdapter adapter = new SessionAdapter(this, session);
        adapter.create();
        childAdded(adapter);

    }

    public void sessionRemoved(final AMQSessionModel<?> session)
    {
    }

    public final void associateVirtualHost(final VirtualHost<?> virtualHost)
    {
        virtualHost.registerConnection(this);
        _virtualHost = virtualHost;
        updateMaxMessageSize();
        _messageAuthorizationRequired = getVirtualHost().getContextValue(Boolean.class, Broker.BROKER_MSG_AUTH);
    }

    @Override
    public TaskExecutor getChildExecutor()
    {
        VirtualHost virtualHost = getVirtualHost();
        if (virtualHost == null)
        {
            return super.getChildExecutor();
        }
        else
        {
            return virtualHost.getTaskExecutor();
        }
    }

    @Override
    public boolean isIncoming()
    {
        return true;
    }

    @Override
    public String getLocalAddress()
    {
        return null;
    }

    @Override
    public String getPrincipal()
    {
        final Principal authorizedPrincipal = getAuthorizedPrincipal();
        return authorizedPrincipal == null ? null : authorizedPrincipal.getName();
    }

    @Override
    public String getRemoteAddress()
    {
        return getRemoteAddressString();
    }

    @Override
    public String getRemoteProcessName()
    {
        return null;
    }

    public Collection<Session> getSessions()
    {
        return getChildren(Session.class);
    }

    @SuppressWarnings("unused")
    @StateTransition( currentState = State.ACTIVE, desiredState = State.DELETED)
    private ListenableFuture<Void> doDelete()
    {
        getEventLogger().message(_logSubject, ConnectionMessages.MODEL_DELETE());
        return closeAsyncIfNotAlreadyClosing();
    }

    @Override
    protected ListenableFuture<Void> beforeClose()
    {
        return closeAsyncIfNotAlreadyClosing();
    }

    private ListenableFuture<Void> closeAsyncIfNotAlreadyClosing()
    {
        if (_modelClosing.compareAndSet(false, true))
        {
            sendConnectionCloseAsync(AMQConstant.CONNECTION_FORCED, "Connection closed by external action");
        }
        return _modelClosedFuture;
    }

    @Override
    public <C extends ConfiguredObject> ListenableFuture<C> addChildAsync(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents)
    {
        if(childClass == Session.class)
        {
            throw new IllegalStateException();
        }
        else
        {
            throw new IllegalArgumentException("Cannot create a child of class " + childClass.getSimpleName());
        }

    }

    @Override
    public long getBytesIn()
    {
        return getDataReceiptStatistics().getTotal();
    }

    @Override
    public long getBytesOut()
    {
        return getDataDeliveryStatistics().getTotal();
    }

    @Override
    public long getMessagesIn()
    {
        return getMessageReceiptStatistics().getTotal();
    }

    @Override
    public long getMessagesOut()
    {
        return getMessageDeliveryStatistics().getTotal();
    }

    public AccessControlContext getAccessControllerContext()
    {
        return _accessControllerContext;
    }

    public final void updateAccessControllerContext()
    {
        _accessControllerContext = org.apache.qpid.server.security.SecurityManager.getAccessControlContextFromSubject(
                getSubject());
    }

    private void logConnectionOpen()
    {
        runAsSubject(new PrivilegedAction<Object>()
        {
            @Override
            public Object run()
            {
                SocketAddress localAddress = _network.getLocalAddress();
                final String localAddressStr;
                if (localAddress instanceof InetSocketAddress)
                {
                    InetSocketAddress inetAddress = (InetSocketAddress) localAddress;
                    localAddressStr = inetAddress.getAddress().getHostAddress() + ":" + inetAddress.getPort();
                }
                else
                {
                    localAddressStr = localAddress.toString();
                }
                getEventLogger().message(ConnectionMessages.OPEN(getPort().getName(),
                                                                 localAddressStr,
                                                                 getProtocol().getProtocolVersion(),
                                                                 getClientId(),
                                                                 getClientVersion(),
                                                                 getClientProduct(),
                                                                 getTransport().isSecure(),
                                                                 getClientId() != null,
                                                                 getClientVersion() != null,
                                                                 getClientProduct() != null));
                return null;
            }
        });
    }

    private void logConnectionClose()
    {
        runAsSubject(new PrivilegedAction<Void>()
        {
            @Override
            public Void run()
            {
                getEventLogger().message(isOrderlyClose()
                                                 ? ConnectionMessages.CLOSE()
                                                 : ConnectionMessages.DROPPED_CONNECTION());
                return null;
            }
        });
    }

    protected void initialiseHeartbeating(final long writerDelay, final long readerDelay)
    {
        if (writerDelay > 0)
        {
            _aggregateTicker.addTicker(new ServerIdleWriteTimeoutTicker(this, (int) writerDelay));
            _network.setMaxWriteIdleMillis(writerDelay);
        }

        if (readerDelay > 0)
        {
            _aggregateTicker.addTicker(new ServerIdleReadTimeoutTicker(_network, this, (int) readerDelay));
            _network.setMaxReadIdleMillis(readerDelay);
        }
    }

    protected abstract boolean isOrderlyClose();

    @Override
    public int getSessionCount()
    {
        return getSessionModels().size();
    }

    @Override
    public void reserveOutboundMessageSpace(final long size)
    {
        _network.reserveOutboundMessageSpace(size);
    }

    protected void markTransportClosed()
    {
        _transportClosedFuture.set(null);
    }

    public LogSubject getLogSubject()
    {
        return _logSubject;
    }

    public EventLogger getEventLogger()
    {
        final VirtualHost<?> virtualHost = getVirtualHost();
        if (virtualHost != null)
        {
            return virtualHost.getEventLogger();
        }
        else
        {
            return getBroker().getEventLogger();
        }
    }

    @Override
    public final boolean isAuthorizedMessagePrincipal(final String userId)
    {
        return !_messageAuthorizationRequired || getAuthorizedPrincipal().getName().equals(userId == null? "" : userId);
    }

    @Override
    public VirtualHost<?> getVirtualHost()
    {
        return _virtualHost;
    }

    public void setVirtualHost(VirtualHost<?> virtualHost)
    {
        associateVirtualHost(virtualHost);

        _messageCompressionThreshold = virtualHost.getContextValue(Integer.class,
                                                                   Broker.MESSAGE_COMPRESSION_THRESHOLD_SIZE);
        if(_messageCompressionThreshold <= 0)
        {
            _messageCompressionThreshold = Integer.MAX_VALUE;
        }

        getSubject().getPrincipals().add(virtualHost.getPrincipal());

        updateAccessControllerContext();
        logConnectionOpen();
    }

    public int getMessageCompressionThreshold()
    {
        return _messageCompressionThreshold;
    }

    @Override
    public String toString()
    {
        return getNetwork().getRemoteAddress() + "(" + ((getAuthorizedPrincipal() == null ? "?" : getAuthorizedPrincipal().getName()) + ")");
    }

    public Principal getAuthorizedPrincipal()
    {
        return AuthenticatedPrincipal.getOptionalAuthenticatedPrincipalFromSubject(getSubject());
    }

    public void setSubject(final Subject subject)
    {
        if (subject == null)
        {
            throw new IllegalArgumentException("subject cannot be null");
        }

        getSubject().getPrincipals().addAll(subject.getPrincipals());
        getSubject().getPrivateCredentials().addAll(subject.getPrivateCredentials());
        getSubject().getPublicCredentials().addAll(subject.getPublicCredentials());

        updateAccessControllerContext();

    }


    private class SlowConnectionOpenTicker implements Ticker, SchedulingDelayNotificationListener
    {
        private final long _allowedTime;
        private volatile long _accumulatedSchedulingDelay;

        SlowConnectionOpenTicker(long timeoutTime)
        {
            _allowedTime = timeoutTime;
        }

        @Override
        public int getTimeToNextTick(final long currentTime)
        {
            return (int) (getCreatedTime() + _allowedTime + _accumulatedSchedulingDelay - currentTime);
        }

        @Override
        public int tick(final long currentTime)
        {
            int nextTick = getTimeToNextTick(currentTime);
            if(nextTick <= 0)
            {
                if (getAuthorizedPrincipal() == null)
                {
                    _logger.warn("Connection has taken more than {} ms to establish identity.  Closing as possible DoS.",
                                 _allowedTime);
                    getEventLogger().message(ConnectionMessages.IDLE_CLOSE("Protocol authentication not established within timeout period", true));
                    _network.close();
                }
                else
                {
                    _aggregateTicker.removeTicker(this);
                    _network.removeSchedulingDelayNotificationListeners(this);
                }
            }
            return nextTick;
        }

        @Override
        public void notifySchedulingDelay(final long schedulingDelay)
        {
            if (schedulingDelay > 0)
            {
                _accumulatedSchedulingDelay += schedulingDelay;
            }
        }
    }
}
