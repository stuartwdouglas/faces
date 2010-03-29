/**
 * 
 */
package org.jboss.seam.faces.context.conversation;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import javax.enterprise.context.Conversation;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.jboss.seam.faces.util.Annotations;
import org.slf4j.Logger;

/**
 * Intercepts methods annotated as Conversational entry points.
 * 
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 * 
 */
@ConversationBoundary
@Interceptor
public class ConversationBoundaryInterceptor implements Serializable
{
   private static final long serialVersionUID = -2729227895205287477L;

   @Inject
   Logger log;

   @Inject
   Conversation conversation;

   @AroundInvoke
   public Object before(final InvocationContext ctx) throws Exception
   {
      Object result = null;
      if (Annotations.hasAnnotation(ctx.getMethod(), Begin.class))
      {
         result = beginConversation(ctx);
      }

      if (Annotations.hasAnnotation(ctx.getMethod(), End.class))
      {
         endConversation(ctx);
      }

      return result;
   }

   private Object beginConversation(final InvocationContext ctx) throws Exception
   {
      String cid = getConversationId(ctx.getMethod());
      if (cid != null)
      {
         conversation.begin(cid);
      }
      else
      {
         conversation.begin();
      }

      log.debug("Began conversation: (#0) on method: (#1.#2(...))", new Object[] { conversation.getId(), ctx.getMethod().getDeclaringClass().getName(), ctx.getMethod().getName() });

      try
      {
         Object result = ctx.proceed();
         return result;
      }
      catch (Exception e)
      {
         conversation.end();
         throw e;
      }
   }

   private void endConversation(final InvocationContext ctx)
   {
      conversation.end();
   }

   private String getConversationId(final Method m)
   {
      String result = null;
      for (Annotation a : m.getAnnotations())
      {
         if (a.annotationType().isAnnotationPresent(Begin.class))
         {
            result = a.annotationType().getAnnotation(Begin.class).id();
         }
      }

      if (result == null)
      {
         for (Annotation a : m.getDeclaringClass().getAnnotations())
         {
            if (a.annotationType().isAnnotationPresent(Begin.class))
            {
               result = a.annotationType().getAnnotation(Begin.class).id();
            }
         }
      }
      return result;
   }
}