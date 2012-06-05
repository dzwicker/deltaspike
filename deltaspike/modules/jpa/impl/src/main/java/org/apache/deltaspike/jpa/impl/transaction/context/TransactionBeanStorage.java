/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.deltaspike.jpa.impl.transaction.context;

import javax.annotation.PreDestroy;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.spi.Contextual;
import javax.persistence.EntityManager;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>This class stores information about
 * &#064;{@link org.apache.deltaspike.jpa.api.TransactionScoped}
 * contextual instances, their {@link javax.enterprise.context.spi.CreationalContext} etc.</p>
 *
 * <p>We use a RequestScoped bean because this way we don't need to take
 * care about cleaning up any ThreadLocals ourselves. This also makes sure that
 * we subsequently destroy any left over TransactionScoped beans (which should not happen,
 * but who knows). We also don't need to do any fancy synchronization stuff since
 * we are sure that we are always in the same Thread.</p>
 */
@RequestScoped
public class TransactionBeanStorage
{
    private static final Logger LOGGER = Logger.getLogger(TransactionBeanStorage.class.getName());

    private static class TransactionContextInfo
    {
        /**
         * This is the actual bean storage.
         * The structure is:
         * <ol>
         *     <li>transactioKey identifies the 'database qualifier'</li>
         *     <li>transactionKey -> Stack: we need the Stack because of REQUIRES_NEW, etc</li>
         *     <li>top Element in the Stack -> Context beans for the transactionKey</li>
         * </ol>
         *
         */
        private Map<Contextual, TransactionBeanEntry> contextualInstances =
                new HashMap<Contextual, TransactionBeanEntry>();

        /** key=qualifier name, value= EntityManager */
        private HashMap<Class, EntityManager> ems = new HashMap<Class, EntityManager>();

        /**
         * counts the 'depth' of the interceptor invocation.
         */
        private AtomicInteger refCounter = new AtomicInteger(0);
    }

    /**
     * If we hit a layer with REQUIRES_NEW, then create a new TransactionContextInfo
     * and push the old one on top of this stack.
     */
    private Stack<TransactionContextInfo> oldTci = new Stack<TransactionContextInfo>();

    /**
     * The TransactionContextInfo which is on top of the stack.
     */
    private TransactionContextInfo currentTci = null;

    /**
     * Increment the ref counter and return the old value.
     * Must only be called if the bean storage is not {@link #isEmpty()}.
     *
     * @return the the previous values of the refCounters. If 0 then we are 'outermost'
     */
    public int incrementRefCounter()
    {
        return currentTci.refCounter.incrementAndGet() - 1;
    }

    /**
     * Decrement the reference counter and return the layer.
     *
     * @return the layer number. 0 represents the outermost interceptor for the qualifier
     */
    public int decrementRefCounter()
    {
        if (currentTci == null)
        {
            return 0;
        }

        return currentTci.refCounter.decrementAndGet();
    }


    /**
     * @return <code>true</code> if we are the outermost interceptor over all qualifiers
     *         and the TransactionBeanStorage is yet empty.
     */
    public boolean isEmpty()
    {
        return currentTci == null;
    }

    /**
     * Start a new TransactionScope
     * @return the
     */
    public void startTransactionScope()
    {
        // first store away any previous TransactionContextInfo
        if (currentTci != null)
        {
            oldTci.push(currentTci);
        }
        currentTci = new TransactionContextInfo();

        if (LOGGER.isLoggable(Level.FINER))
        {
            LOGGER.finer( "starting TransactionScope");
        }
    }

    /**
     * End the TransactionScope with the given qualifier.
     * This will subsequently destroy all beans which are stored
     * in the context.
     *
     * This method only gets used if we leave a transaction with REQUIRES_NEW.
     * In all other cases we use {@link #endAllTransactionScopes()}.
     */
    public void endTransactionScope()
    {
        if (LOGGER.isLoggable(Level.FINER))
        {
            LOGGER.finer("ending TransactionScope");
        }

        destroyBeans(currentTci.contextualInstances);

        if (oldTci.size() > 0)
        {
            currentTci = oldTci.pop();
        }
        else
        {
            currentTci = null;
        }
    }


    public EntityManager storeUsedEntityManager(Class<? extends Annotation> emQualifier, EntityManager entityManager)
    {
        return currentTci.ems.put(emQualifier, entityManager);
    }

    public HashMap<Class,EntityManager> getUsedEntityManagers()
    {
        return currentTci.ems;
    }

    public void cleanUsedEntityManagers()
    {
        currentTci.ems.clear();
    }

    /**
     * @return the Map which represents the currently active Context content.
     */
    public Map<Contextual, TransactionBeanEntry> getActiveTransactionContext()
    {
        if (currentTci == null)
        {
            return null;
        }

        return currentTci.contextualInstances;
    }

    /**
     * At the end of the request we will destroy all beans still
     * stored in the context.
     */
    @PreDestroy
    public void requestEnded()
    {
        endAllTransactionScopes();
    }

    private void endAllTransactionScopes()
    {
        while (!isEmpty())
        {
            endTransactionScope();
        }
    }

    /**
     * Properly destroy all the given beans.
     * @param activeBeans
     */
    private void destroyBeans(Map<Contextual, TransactionBeanEntry> activeBeans)
    {
        for (TransactionBeanEntry beanBag : activeBeans.values())
        {
            beanBag.getBean().destroy(beanBag.getContextualInstance(), beanBag.getCreationalContext());
        }
    }
}

