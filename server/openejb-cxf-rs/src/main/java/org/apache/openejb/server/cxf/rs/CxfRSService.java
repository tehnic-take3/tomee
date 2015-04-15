/*
 *     Licensed to the Apache Software Foundation (ASF) under one or more
 *     contributor license agreements.  See the NOTICE file distributed with
 *     this work for additional information regarding copyright ownership.
 *     The ASF licenses this file to You under the Apache License, Version 2.0
 *     (the "License"); you may not use this file except in compliance with
 *     the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package org.apache.openejb.server.cxf.rs;

import java.util.ArrayList;
import org.apache.cxf.Bus;
import org.apache.cxf.binding.BindingFactoryManager;
import org.apache.cxf.jaxrs.JAXRSBindingFactory;
import org.apache.openejb.assembler.classic.AppInfo;
import org.apache.openejb.assembler.classic.WebAppInfo;
import org.apache.openejb.assembler.classic.event.AssemblerAfterApplicationCreated;
import org.apache.openejb.assembler.classic.event.AssemblerBeforeApplicationDestroyed;
import org.apache.openejb.cdi.WebBeansContextCreated;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.observer.Observes;
import org.apache.openejb.rest.AbstractRestThreadLocalProxy;
import org.apache.openejb.rest.RESTResourceFinder;
import org.apache.openejb.rest.ThreadLocalContextManager;
import org.apache.openejb.server.ServiceException;
import org.apache.openejb.server.cxf.transport.HttpTransportFactory;
import org.apache.openejb.server.cxf.transport.util.CxfUtil;
import org.apache.openejb.server.rest.RESTService;
import org.apache.openejb.server.rest.RsHttpListener;
import org.apache.openejb.spi.ContainerSystem;
import org.apache.webbeans.annotation.AnyLiteral;
import org.apache.webbeans.annotation.EmptyAnnotationLiteral;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.container.BeanManagerImpl;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.util.AnnotationLiteral;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Providers;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static java.util.Arrays.asList;

public class CxfRSService extends RESTService {

    private static final String NAME = "cxf-rs";
    private HttpTransportFactory httpTransportFactory;

    @Override
    public void service(final InputStream in, final OutputStream out) throws ServiceException, IOException {
        throw new UnsupportedOperationException(getClass().getName() + " cannot be invoked directly");
    }

    @Override
    public void service(final Socket socket) throws ServiceException, IOException {
        throw new UnsupportedOperationException(getClass().getName() + " cannot be invoked directly");
    }

    @Override
    public String getName() {
        return NAME;
    }

    public void integrateCDIAndJaxRsInjections(@Observes final WebBeansContextCreated event) {
        contextCDIIntegration(event.getContext());
    }

    private void contextCDIIntegration(final WebBeansContext wbc) {
        final BeanManagerImpl beanManagerImpl = wbc.getBeanManagerImpl();
        beanManagerImpl.addAdditionalQualifier(Context.class);
        beanManagerImpl.addInternalBean(new ContextBean<SecurityContext>(SecurityContext.class, ThreadLocalContextManager.SECURITY_CONTEXT));
        beanManagerImpl.addInternalBean(new ContextBean<UriInfo>(UriInfo.class, ThreadLocalContextManager.URI_INFO));
        beanManagerImpl.addInternalBean(new ContextBean<HttpServletRequest>(HttpServletRequest.class, ThreadLocalContextManager.HTTP_SERVLET_REQUEST));
        beanManagerImpl.addInternalBean(new ContextBean<HttpServletResponse>(HttpServletResponse.class, ThreadLocalContextManager.HTTP_SERVLET_RESPONSE));
        beanManagerImpl.addInternalBean(new ContextBean<HttpHeaders>(HttpHeaders.class, ThreadLocalContextManager.HTTP_HEADERS));
        beanManagerImpl.addInternalBean(new ContextBean<Request>(Request.class, ThreadLocalContextManager.REQUEST));
        beanManagerImpl.addInternalBean(new ContextBean<ServletRequest>(ServletRequest.class, ThreadLocalContextManager.SERVLET_REQUEST));
        beanManagerImpl.addInternalBean(new ContextBean<ServletContext>(ServletContext.class, ThreadLocalContextManager.SERVLET_CONTEXT));
        beanManagerImpl.addInternalBean(new ContextBean<ServletConfig>(ServletConfig.class, ThreadLocalContextManager.SERVLET_CONFIG));
        beanManagerImpl.addInternalBean(new ContextBean<Providers>(Providers.class, ThreadLocalContextManager.PROVIDERS));
        beanManagerImpl.addInternalBean(new ContextBean<ContextResolver>(ContextResolver.class, ThreadLocalContextManager.CONTEXT_RESOLVER));
    }

    @Override
    public void init(final Properties properties) throws Exception {
        super.init(properties);
        SystemInstance.get().setComponent(RESTResourceFinder.class, new CxfRESTResourceFinder());

        CxfUtil.configureBus();

        final Bus bus = CxfUtil.getBus();

        final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CxfUtil.initBusLoader());
        try {
            // force init of bindings
            if (!CxfUtil.hasService(JAXRSBindingFactory.JAXRS_BINDING_ID)) {
                // cxf does it but with the pattern "if not here install it". It is slow so installing it without testing for presence here.
                final BindingFactoryManager bfm = bus.getExtension(BindingFactoryManager.class);
                try {
                    bfm.registerBindingFactory(JAXRSBindingFactory.JAXRS_BINDING_ID, new JAXRSBindingFactory(bus));
                } catch (final Throwable b) {
                    // no-op
                }
            }
        } finally {
            if (oldLoader != null) {
                CxfUtil.clearBusLoader(oldLoader);
            }
        }
    }

    @Override
    public void stop() throws ServiceException {
        super.stop();
        CxfUtil.release();
    }

    @Override
    protected void beforeStart() {
        super.beforeStart();
        httpTransportFactory = new HttpTransportFactory(CxfUtil.getBus());
    }

    @Override
    protected boolean containsJaxRsConfiguration(final Properties properties) {
        return properties.containsKey(CxfRsHttpListener.PROVIDERS_KEY)
            || properties.containsKey(CxfRsHttpListener.CXF_JAXRS_PREFIX + CxfUtil.IN_FAULT_INTERCEPTORS)
            || properties.containsKey(CxfRsHttpListener.CXF_JAXRS_PREFIX + CxfUtil.IN_INTERCEPTORS)
            || properties.containsKey(CxfRsHttpListener.CXF_JAXRS_PREFIX + CxfUtil.OUT_FAULT_INTERCEPTORS)
            || properties.containsKey(CxfRsHttpListener.CXF_JAXRS_PREFIX + CxfUtil.OUT_INTERCEPTORS)
            || properties.containsKey(CxfRsHttpListener.CXF_JAXRS_PREFIX + CxfUtil.DATABINDING)
            || properties.containsKey(CxfRsHttpListener.CXF_JAXRS_PREFIX + CxfUtil.FEATURES)
            || properties.containsKey(CxfRsHttpListener.CXF_JAXRS_PREFIX + CxfUtil.ADDRESS)
            || properties.containsKey(CxfRsHttpListener.CXF_JAXRS_PREFIX + CxfUtil.ENDPOINT_PROPERTIES);
    }

    @Override
    protected RsHttpListener createHttpListener() {
        return new CxfRsHttpListener(httpTransportFactory, getWildcard());
    }

    private static class ContextLiteral extends EmptyAnnotationLiteral<Context> implements Context {
        private static final long serialVersionUID = 1L;

        public static final AnnotationLiteral<Context> INSTANCE = new ContextLiteral();
    }

    private static class ContextBean<T> implements Bean<T> {
        private final Class<T> type;
        private final Set<Type> types;
        private final Set<Annotation> qualifiers;
        private final T proxy;

        public ContextBean(final Class<T> type, final AbstractRestThreadLocalProxy<T> proxy) {
            this.type = type;
            this.proxy =
                (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[]{type, Serializable.class}, new DelegateHandler(proxy));
            this.types = new HashSet<Type>(asList(Object.class, type));
            this.qualifiers = new HashSet<Annotation>(asList(ContextLiteral.INSTANCE, AnyLiteral.INSTANCE));
        }

        @Override
        public Set<Type> getTypes() {
            return types;
        }

        @Override
        public Set<Annotation> getQualifiers() {
            return qualifiers;
        }

        @Override
        public Class<? extends Annotation> getScope() {
            return ApplicationScoped.class;
        }

        @Override
        public String getName() {
            return null;
        }

        @Override
        public boolean isNullable() {
            return false;
        }

        @Override
        public Set<InjectionPoint> getInjectionPoints() {
            return Collections.<InjectionPoint>emptySet();
        }

        @Override
        public Class<?> getBeanClass() {
            return type;
        }

        @Override
        public Set<Class<? extends Annotation>> getStereotypes() {
            return Collections.<Class<? extends Annotation>>emptySet();
        }

        @Override
        public boolean isAlternative() {
            return false;
        }

        @Override
        public T create(final CreationalContext<T> tCreationalContext) {
            return proxy;
        }

        @Override
        public void destroy(final T t, final CreationalContext<T> tCreationalContext) {
            // no-op
        }
    }

    private static class DelegateHandler<T> implements InvocationHandler {
        private final AbstractRestThreadLocalProxy<T> proxy;

        public DelegateHandler(final AbstractRestThreadLocalProxy<T> proxy) {
            this.proxy = proxy;
        }

        @Override
        public Object invoke(final Object ignored, final Method method, final Object[] args) throws Throwable {
            try {
                return method.invoke(proxy.get(), args);
            } catch (final InvocationTargetException ite) {
                throw ite.getCause();
            }
        }
    }
}
