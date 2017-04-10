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
 */

package org.apache.qpid.tests.protocol.v1_0;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.plugin.PluggableService;

@PluggableService
public class ExternalQpidBrokerAdminImpl implements BrokerAdmin
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalQpidBrokerAdminImpl.class);

    public ExternalQpidBrokerAdminImpl()
    {
        LOGGER.debug("QpidBrokerAdminImpl ctor");
    }

    @Override
    public void beforeTestClass(final Class testClass)
    {
        LOGGER.debug("beforeTestClass");
    }

    @Override
    public void beforeTestMethod(final Class testClass, final Method method)
    {
        LOGGER.debug("beforeTestMethod");
    }

    @Override
    public void afterTestMethod(final Class testClass, final Method method)
    {
        LOGGER.debug("afterTestMethod");
    }

    @Override
    public void afterTestClass(final Class testClass)
    {
        LOGGER.debug("afterTestClass");
    }

    @Override
    public InetSocketAddress getBrokerAddress(final PortType portType)
    {
        Integer port;
        switch (portType)
        {
            case AMQP:
                port = Integer.getInteger("qpid.tests.protocol.broker.external.port.standard");
                break;
            case ANONYMOUS_AMQP:
                port = Integer.getInteger("qpid.tests.protocol.broker.external.port.anonymous");
                break;
            default:
                throw new IllegalArgumentException(String.format("Unknown port type '%s'", portType));
        }
        return new InetSocketAddress(port);
    }

    @Override
    public void createQueue(final String queueName)
    {
        LOGGER.debug(String.format("creation of queue '%s' requested", queueName));
    }

    @Override
    public void deleteQueue(final String queueName)
    {
        LOGGER.debug(String.format("deletion of queue '%s' requested", queueName));
    }

    @Override
    public String getType()
    {
        return "EXTERNAL_BROKER";
    }
}
