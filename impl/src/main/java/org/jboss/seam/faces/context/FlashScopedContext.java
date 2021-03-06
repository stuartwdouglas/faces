/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.seam.faces.context;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.Contextual;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.BeanManager;
import javax.faces.bean.FlashScoped;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.faces.event.PhaseEvent;
import javax.faces.event.PhaseId;
import javax.faces.event.PhaseListener;
import javax.inject.Named;

import org.jboss.seam.faces.util.BeanManagerUtils;
import org.jboss.weld.extensions.beanManager.BeanManagerAccessor;

/**
 * This class provides the lifecycle for the new JSF 2 Flash Context
 * 
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
@RequestScoped
public class FlashScopedContext implements Context, PhaseListener, Serializable
{
   private static final long serialVersionUID = -1580689204988513798L;

   private static final String SESSION_KEY_PREFIX = FlashScopedContext.class.getName() + ".context";
   private final static String COMPONENT_MAP_NAME = FlashScopedContext.class.getName() + ".componentInstanceMap";
   private final static String CREATIONAL_MAP_NAME = FlashScopedContext.class.getName() + ".creationalInstanceMap";

   String requestParameterName = "fid";

   FlashContext currentContext = null;

   @Produces
   @Named
   @RequestScoped
   public FlashContext getFlashContext()
   {
      if (currentContext == null)
      {
         initializeCurrentContext();
      }
      return currentContext;
   }

   private FlashContext getCurrentFlashContext()
   {
      BeanManager manager = BeanManagerAccessor.getBeanManager();
      return BeanManagerUtils.getContextualInstance(manager, FlashContext.class);
   }

   private void assertActive()
   {
      if (!isActive())
      {
         throw new ContextNotActiveException("Seam context with scope annotation @FlashScoped is not active with respect to the current thread. " +
               "This is probably caused by attempting to access the Flash before it was created or after it was destroyed.");
      }
   }

   private void initializeCurrentContext()
   {
      Integer currentId = getCurrentId();

      if (savedContextExists(currentId))
      {
         // getFlashForCurrentIdAndReferrer
         FlashContext context = (FlashContext) getSessionMap().get(getSessionKey(currentId));
         currentContext = context;
      }
      else
      {
         FlashContextImpl context = new FlashContextImpl();
         context.setId(getNextFlashId());
         getSessionMap().put(getSessionKey(context.getId()), context);
         currentContext = context;
      }

   }

   private int getNextFlashId()
   {
      Map<String, Object> sessionMap = getSessionMap();
      int id = 0;

      while (sessionMap.containsKey(getSessionKey(id)))
      {
         id++;
      }
      return id;
   }

   private boolean savedContextExists(final int id)
   {
      return getSessionMap().get(getSessionKey(id)) instanceof FlashContext;
   }

   private Integer getCurrentId()
   {
      Map<String, String> requestParameterMap = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap();
      String currentId = requestParameterMap.get(requestParameterName);
      Integer result = null;
      try
      {
         if (currentId != null)
         {
            result = Integer.valueOf(currentId);
         }
      }
      catch (NumberFormatException e)
      {
         // ignored!
      }
      return result;
   }

   /**
    * Destroy the current context since Render Response has completed.
    */
   @SuppressWarnings("unchecked")
   public void afterPhase(final PhaseEvent event)
   {
      if (PhaseId.RENDER_RESPONSE.equals(event.getPhaseId()))
      {
         getSessionMap().remove(getSessionKey(getFlashContext().getId()));

         Map<Contextual<?>, Object> componentInstanceMap = getComponentInstanceMap();
         Map<Contextual<?>, CreationalContext<?>> creationalContextMap = getCreationalContextMap();

         if ((componentInstanceMap != null) && (creationalContextMap != null))
         {
            for (Entry<Contextual<?>, Object> componentEntry : componentInstanceMap.entrySet())
            {
               Contextual contextual = componentEntry.getKey();
               Object instance = componentEntry.getValue();
               CreationalContext creational = creationalContextMap.get(contextual);

               contextual.destroy(instance, creational);
            }
         }
      }
   }

   public void beforePhase(PhaseEvent arg0)
   {
      /* intentionally empty */
   }

   public PhaseId getPhaseId()
   {
      return PhaseId.ANY_PHASE;
   }

   /*
    * Context Methods
    */
   @SuppressWarnings("unchecked")
   public <T> T get(final Contextual<T> component)
   {
      assertActive();
      return (T) getComponentInstanceMap().get(component);
   }

   @SuppressWarnings("unchecked")
   public <T> T get(final Contextual<T> component, final CreationalContext<T> creationalContext)
   {
      assertActive();

      T instance = get(component);

      if (instance == null)
      {
         Map<Contextual<?>, CreationalContext<?>> creationalContextMap = getCreationalContextMap();
         Map<Contextual<?>, Object> componentInstanceMap = getComponentInstanceMap();

         synchronized (componentInstanceMap)
         {
            instance = (T) componentInstanceMap.get(component);
            if (instance == null)
            {
               instance = component.create(creationalContext);

               if (instance != null)
               {
                  componentInstanceMap.put(component, instance);
                  creationalContextMap.put(component, creationalContext);
               }
            }
         }
      }

      return instance;
   }

   public boolean isActive()
   {
      return FacesContext.getCurrentInstance() != null;
   }

   public Class<? extends Annotation> getScope()
   {
      return FlashScoped.class;
   }

   /*
    * Helpers for manipulating the Component/Context maps.
    */
   @SuppressWarnings("unchecked")
   private Map<Contextual<?>, Object> getComponentInstanceMap()
   {
      ConcurrentHashMap<Contextual<?>, Object> map = (ConcurrentHashMap<Contextual<?>, Object>) getCurrentFlashContext().get(COMPONENT_MAP_NAME);

      if (map == null)
      {
         map = new ConcurrentHashMap<Contextual<?>, Object>();
         getCurrentFlashContext().put(COMPONENT_MAP_NAME, map);
      }

      return map;
   }

   @SuppressWarnings("unchecked")
   private Map<Contextual<?>, CreationalContext<?>> getCreationalContextMap()
   {
      Map<Contextual<?>, CreationalContext<?>> map = (ConcurrentHashMap<Contextual<?>, CreationalContext<?>>) getCurrentFlashContext().get(CREATIONAL_MAP_NAME);

      if (map == null)
      {
         map = new ConcurrentHashMap<Contextual<?>, CreationalContext<?>>();
         getCurrentFlashContext().put(CREATIONAL_MAP_NAME, map);
      }

      return map;
   }

   private Map<String, Object> getSessionMap()
   {
      ExternalContext externalContext = FacesContext.getCurrentInstance().getExternalContext();
      return externalContext.getSessionMap();
   }

   private String getSessionKey(final int id)
   {
      String result = SESSION_KEY_PREFIX;
      result += "_" + id;
      return result;
   }

   /**
    * Get the name of the request parameter to contain the current
    * {@link FlashContext} id.
    */
   public String getRequestParameterName()
   {
      return requestParameterName;
   }
}
