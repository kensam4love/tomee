/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.resource.jdbc.managed;

import org.apache.openejb.OpenEJB;
import org.apache.openejb.resource.jdbc.managed.local.LocalXAResource;

import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.xa.XAResource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ManagedConnection implements InvocationHandler {
    private static final Map<Transaction, Connection> CONNECTION_BY_TX = new ConcurrentHashMap<Transaction, Connection>();

    protected Connection delegate;
    protected XAResource xaResource;

    private Transaction currentTransaction;

    protected ManagedConnection(final Connection connection, final XAResource resource) {
        delegate = connection;
        xaResource = resource;
    }

    public ManagedConnection(final Connection connection) {
        this(connection, new LocalXAResource(connection));
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        try {
            final TransactionManager transactionManager = OpenEJB.getTransactionManager();
            final Transaction transaction = transactionManager.getTransaction();

            if (transaction == null) { // shouldn't be possible
                return method.invoke(delegate, args);
            }

            if (currentTransaction != null) {
                if (isUnderTransaction(currentTransaction.getStatus())) {
                    if (currentTransaction != transaction) {
                        throw new SQLException("Connection can not be used while enlisted in another transaction");
                    }
                    return invokeUnderTransaction(delegate, method, args);
                } else {
                    System.out.println("no tx close");
                    close(delegate);
                }
            }

            int status = transaction.getStatus();
            if (isUnderTransaction(status)) {
                final Connection connection = CONNECTION_BY_TX.get(transaction);
                if (connection != null) { // shared one
                    System.out.println("conn != null close");
                    delegate.close(); // return to pool
                    delegate = connection;
                } else {
                    CONNECTION_BY_TX.put(transaction, delegate);
                    currentTransaction = transaction;
                    try {
                        transaction.enlistResource(xaResource);
                    } catch (RollbackException ignored) {
                        // no-op
                    } catch (SystemException e) {
                        throw new SQLException("Unable to enlist connection the transaction", e);
                    }
                }
                transaction.registerSynchronization(new ClosingSynchronization(delegate));

                delegate.setAutoCommit(false); // TODO: previous value?

                return invokeUnderTransaction(delegate, method, args);
            }


            return method.invoke(delegate, args);
        } catch (InvocationTargetException ite) {
            throw ite.getTargetException();
        }
    }

    private static Object invokeUnderTransaction(final Connection delegate, final Method method, final Object[] args) throws Exception, IllegalAccessException {
        final String mtdName = method.getName();
        if ("setAutoCommit".equals(mtdName)
                || "commit".equals(mtdName)
                || "rollback".equals(mtdName)
                || "setSavepoint".equals(mtdName)
                || "setReadOnly".equals(mtdName)) {
            throw forbiddenCall(mtdName);
        }
        if ("close".equals(mtdName)) {
            // will be done later
            // we need to differ it in case of rollback
            return null;
        }
        return method.invoke(delegate, args);
    }

    private static boolean isUnderTransaction(int status) {
        return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK;
    }

    private static SQLException forbiddenCall(final String mtdName) {
        return new SQLException("can't call " + mtdName + " when the connection is JtaManaged");
    }

    private static void close(final Connection connection) {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // no-op
        }
    }

    private static class ClosingSynchronization implements Synchronization {
        private final Connection connection;

        public ClosingSynchronization(final Connection delegate) {
            connection = delegate;
        }

        @Override
        public void beforeCompletion() {
            // no-op
        }

        @Override
        public void afterCompletion(int status) {
            close(connection);
            try {
                final Transaction tx = OpenEJB.getTransactionManager().getTransaction();
                CONNECTION_BY_TX.remove(tx);
            } catch (SystemException ignored) {
                // no-op
            }
        }
    }
}
