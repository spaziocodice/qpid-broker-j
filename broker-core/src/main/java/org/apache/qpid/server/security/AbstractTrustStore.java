/*
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
package org.apache.qpid.server.security;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.logging.messages.TrustStoreMessages;
import org.apache.qpid.server.model.AbstractConfigurationChangeListener;
import org.apache.qpid.server.model.AbstractConfiguredObject;
import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.IntegrityViolationException;
import org.apache.qpid.server.model.ManagedAttributeField;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.TrustStore;
import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.server.security.auth.manager.SimpleLDAPAuthenticationManager;

public abstract class AbstractTrustStore<X extends AbstractTrustStore<X>>
        extends AbstractConfiguredObject<X> implements TrustStore<X>
{
    private static Logger LOGGER = LoggerFactory.getLogger(AbstractTrustStore.class);

    protected static final long ONE_DAY = 24L * 60L * 60L * 1000L;

    private final Broker<?> _broker;
    private final EventLogger _eventLogger;

    @ManagedAttributeField
    private boolean _exposedAsMessageSource;
    @ManagedAttributeField
    private List<VirtualHostNode<?>> _includedVirtualHostNodeMessageSources;
    @ManagedAttributeField
    private List<VirtualHostNode<?>> _excludedVirtualHostNodeMessageSources;

    private ScheduledFuture<?> _checkExpiryTaskFuture;

    public AbstractTrustStore(Map<String, Object> attributes, Broker<?> broker)
    {
        super(broker, attributes);

        _broker = broker;
        _eventLogger = broker.getEventLogger();
        _eventLogger.message(TrustStoreMessages.CREATE(getName()));
    }

    public final Broker<?> getBroker()
    {
        return _broker;
    }

    final EventLogger getEventLogger()
    {
        return _eventLogger;
    }

    @Override
    protected ListenableFuture<Void> onClose()
    {
        if(_checkExpiryTaskFuture != null)
        {
            _checkExpiryTaskFuture.cancel(false);
            _checkExpiryTaskFuture = null;
        }
        return Futures.immediateFuture(null);
    }

    @Override
    protected void logOperation(final String operation)
    {
        _broker.getEventLogger().message(TrustStoreMessages.OPERATION(operation));
    }

    protected void initializeExpiryChecking()
    {
        int checkFrequency = getCertificateExpiryCheckFrequency();
        if(getBroker().getState() == State.ACTIVE)
        {
            _checkExpiryTaskFuture = getBroker().scheduleHouseKeepingTask(checkFrequency, TimeUnit.DAYS,
                                                                          this::checkCertificateExpiry);
        }
        else
        {
            final int frequency = checkFrequency;
            getBroker().addChangeListener(new AbstractConfigurationChangeListener()
            {
                @Override
                public void stateChanged(final ConfiguredObject<?> object, final State oldState, final State newState)
                {
                    if (newState == State.ACTIVE)
                    {
                        _checkExpiryTaskFuture =
                                getBroker().scheduleHouseKeepingTask(frequency, TimeUnit.DAYS,
                                                                     () -> checkCertificateExpiry());
                        getBroker().removeChangeListener(this);
                    }
                }
            });
        }
    }

    protected final ListenableFuture<Void> deleteIfNotInUse()
    {
        // verify that it is not in use
        String storeName = getName();

        Collection<Port<?>> ports = new ArrayList<>(_broker.getPorts());
        for (Port port : ports)
        {
            Collection<TrustStore> trustStores = port.getTrustStores();
            if(trustStores != null)
            {
                for (TrustStore store : trustStores)
                {
                    if(storeName.equals(store.getAttribute(TrustStore.NAME)))
                    {
                        throw new IntegrityViolationException("Trust store '"
                                                              + storeName
                                                              + "' can't be deleted as it is in use by a port: "
                                                              + port.getName());
                    }
                }
            }
        }

        Collection<AuthenticationProvider> authenticationProviders = new ArrayList<>(_broker.getAuthenticationProviders());
        for (AuthenticationProvider authProvider : authenticationProviders)
        {
            if (authProvider instanceof SimpleLDAPAuthenticationManager)
            {
                SimpleLDAPAuthenticationManager simpleLdap = (SimpleLDAPAuthenticationManager) authProvider;
                if (simpleLdap.getTrustStore() == this)
                {
                    throw new IntegrityViolationException("Trust store '"
                                                          + storeName
                                                          + "' can't be deleted as it is in use by an authentication manager: "
                                                          + authProvider.getName());
                }
            }
        }
        deleted();
        setState(State.DELETED);
        _eventLogger.message(TrustStoreMessages.DELETE(getName()));
        return Futures.immediateFuture(null);
    }

    protected abstract void checkCertificateExpiry();

    protected void checkCertificateExpiry(final long currentTime,
                                          final Date expiryTestDate,
                                          final X509Certificate cert)
    {
        try
        {
            cert.checkValidity(expiryTestDate);
        }
        catch(CertificateExpiredException e)
        {
            long timeToExpiry = cert.getNotAfter().getTime() - currentTime;
            int days = Math.max(0,(int)(timeToExpiry / (ONE_DAY)));

            getEventLogger().message(TrustStoreMessages.EXPIRING(getName(), String.valueOf(days), cert.getSubjectDN().toString()));
        }
        catch(CertificateNotYetValidException e)
        {
            // ignore
        }
    }

    @Override
    public final int getCertificateExpiryWarnPeriod()
    {
        try
        {
            return getContextValue(Integer.class, CERTIFICATE_EXPIRY_WARN_PERIOD);
        }
        catch (NullPointerException | IllegalArgumentException e)
        {
            LOGGER.warn("The value of the context variable '{}' for truststore {} cannot be converted to an integer. The value {} will be used as a default", CERTIFICATE_EXPIRY_WARN_PERIOD, getName(), DEFAULT_CERTIFICATE_EXPIRY_WARN_PERIOD);
            return DEFAULT_CERTIFICATE_EXPIRY_WARN_PERIOD;
        }
    }

    @Override
    public int getCertificateExpiryCheckFrequency()
    {
        int checkFrequency;
        try
        {
            checkFrequency = getContextValue(Integer.class, CERTIFICATE_EXPIRY_CHECK_FREQUENCY);
        }
        catch (IllegalArgumentException | NullPointerException e)
        {
            LOGGER.warn("Cannot parse the context variable {} ", CERTIFICATE_EXPIRY_CHECK_FREQUENCY, e);
            checkFrequency = DEFAULT_CERTIFICATE_EXPIRY_CHECK_FREQUENCY;
        }
        return checkFrequency;
    }

    @Override
    public boolean isExposedAsMessageSource()
    {
        return _exposedAsMessageSource;
    }

    @Override
    public List<VirtualHostNode<?>> getIncludedVirtualHostNodeMessageSources()
    {
        return _includedVirtualHostNodeMessageSources;
    }

    @Override
    public List<VirtualHostNode<?>> getExcludedVirtualHostNodeMessageSources()
    {
        return _excludedVirtualHostNodeMessageSources;
    }
}
