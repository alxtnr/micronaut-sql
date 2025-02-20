/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.configuration.hibernate.jpa;


import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.transaction.hibernate5.MicronautSessionContext;
import org.hibernate.Interceptor;
import org.hibernate.MappingException;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.SessionFactoryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.resource.beans.container.spi.BeanContainer;
import org.hibernate.resource.beans.container.spi.ContainedBean;
import org.hibernate.resource.beans.spi.BeanInstanceProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import javax.validation.ValidatorFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A factory bean for constructing the {@link javax.persistence.EntityManagerFactory}.
 *
 * @author graemerocher
 * @since 1.0
 * @deprecated Class is deprecated to be removed.
 */
@Deprecated
public class EntityManagerFactoryBean {

    private static final Logger LOG = LoggerFactory.getLogger(EntityManagerFactoryBean.class);

    private final ApplicationContext applicationContext;
    private final JpaConfiguration jpaConfiguration;

    /**
     * @param jpaConfiguration   The JPA configuration
     * @param integrator         The integration
     * @param applicationContext The application context
     */
    public EntityManagerFactoryBean(
            @Primary @Nullable JpaConfiguration jpaConfiguration,
            @Primary @Nullable Integrator integrator,
            ApplicationContext applicationContext) {
        this.jpaConfiguration = jpaConfiguration != null ? jpaConfiguration : new JpaConfiguration(
                applicationContext,
                integrator
        );
        this.applicationContext = applicationContext;
    }

    /**
     * Builds the {@link StandardServiceRegistry} bean for the given {@link DataSource}.
     *
     * @param dataSourceName The data source name
     * @param dataSource     The data source
     * @return The {@link StandardServiceRegistry}
     */
    protected StandardServiceRegistry hibernateStandardServiceRegistry(
            @Parameter String dataSourceName,
            DataSource dataSource) {

        final DataSourceResolver dataSourceResolver = applicationContext.findBean(DataSourceResolver.class).orElse(null);
        if (dataSourceResolver != null) {
            dataSource = dataSourceResolver.resolve(dataSource);
        }

        Map<String, Object> additionalSettings = new LinkedHashMap<>();
        additionalSettings.put(AvailableSettings.DATASOURCE, dataSource);
        additionalSettings.put(AvailableSettings.CURRENT_SESSION_CONTEXT_CLASS,
                applicationContext.findBean(HibernateCurrentSessionContextClassProvider.class)
                        .map(provider -> provider.get().getName()).orElseGet(MicronautSessionContext.class::getName));
        additionalSettings.put(AvailableSettings.SESSION_FACTORY_NAME, dataSourceName);
        additionalSettings.put(AvailableSettings.SESSION_FACTORY_NAME_IS_JNDI, false);
        additionalSettings.put(AvailableSettings.BEAN_CONTAINER, new BeanContainer() {
            @Override
            public <B> ContainedBean<B> getBean(Class<B> beanType, LifecycleOptions lifecycleOptions, BeanInstanceProducer fallbackProducer) {
                B bean = applicationContext.findBean(beanType)
                        .orElseGet(() -> fallbackProducer.produceBeanInstance(beanType));
                return () -> bean;
            }

            @Override
            public <B> ContainedBean<B> getBean(
                    String name,
                    Class<B> beanType,
                    LifecycleOptions lifecycleOptions,
                    BeanInstanceProducer fallbackProducer) {
                B bean = applicationContext.findBean(beanType, Qualifiers.byName(name))
                        .orElseGet(() -> fallbackProducer.produceBeanInstance(name, beanType));
                return () -> bean;
            }

            @Override
            public void stop() {
                // no-op, managed externally
            }
        });
        JpaConfiguration jpaConfiguration = applicationContext.findBean(JpaConfiguration.class, Qualifiers.byName(dataSourceName))
                .orElse(this.jpaConfiguration);
        return jpaConfiguration.buildStandardServiceRegistry(
                additionalSettings
        );
    }

    /**
     * Builds the {@link MetadataSources} for the given {@link StandardServiceRegistry}.
     *
     * @param jpaConfiguration        The JPA configuration
     * @param standardServiceRegistry The standard service registry
     * @return The {@link MetadataSources}
     */
    protected MetadataSources hibernateMetadataSources(
            @Parameter @Nullable JpaConfiguration jpaConfiguration,
            StandardServiceRegistry standardServiceRegistry) {

        if (jpaConfiguration == null) {
            jpaConfiguration = this.jpaConfiguration;
        }
        MetadataSources metadataSources = createMetadataSources(standardServiceRegistry);

        jpaConfiguration.getEntityScanConfiguration().findEntities().forEach(metadataSources::addAnnotatedClass);

        if (jpaConfiguration.getMappingResources() != null) {
            for (String resource : jpaConfiguration.getMappingResources()) {
                metadataSources.addResource(resource);
            }
        }

        if (metadataSources.getAnnotatedClasses().isEmpty()) {
            String[] packages = jpaConfiguration.getEntityScanConfiguration().getPackages();
            if (ArrayUtils.isEmpty(packages)) {
                packages = applicationContext.getEnvironment().getPackages().toArray(StringUtils.EMPTY_STRING_ARRAY);
            }
            throw new ConfigurationException("Entities not found for JPA configuration: '" + jpaConfiguration.getName() + "' within packages [" + String.join(",", packages) + "]. Check that you have correctly specified a package containing JPA entities within the \"jpa." + jpaConfiguration.getName() + ".entity-scan.packages\" property in your application configuration and that those entities are either compiled with Micronaut or a build time index produced with @Introspected(packages=\"foo.bar\", includedAnnotations=Entity.class) declared on your Application class");
        }
        return metadataSources;
    }

    /**
     * Builds the {@link SessionFactoryBuilder} to use.
     *
     * @param metadataSources      The {@link MetadataSources}
     * @param validatorFactory     The {@link ValidatorFactory}
     * @param hibernateInterceptor The {@link Interceptor}
     * @return The {@link SessionFactoryBuilder}
     */
    protected SessionFactoryBuilder hibernateSessionFactoryBuilder(
            MetadataSources metadataSources,
            @Nullable ValidatorFactory validatorFactory,
            @Nullable Interceptor hibernateInterceptor) {

        try {
            Metadata metadata = metadataSources.buildMetadata();
            SessionFactoryBuilder sessionFactoryBuilder = metadata.getSessionFactoryBuilder();
            if (validatorFactory != null) {
                sessionFactoryBuilder.applyValidatorFactory(validatorFactory);
            }

            if (hibernateInterceptor != null) {
                sessionFactoryBuilder.applyInterceptor(hibernateInterceptor);
            }
            return sessionFactoryBuilder;
        } catch (MappingException e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Hibernate mapping error", e);
            }
            throw e;
        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Error creating SessionFactoryBuilder", e);
            }
            throw e;
        }
    }

    /**
     * Builds the actual {@link SessionFactory} from the builder.
     *
     * @param sessionFactoryBuilder The {@link SessionFactoryBuilder}
     * @return The {@link SessionFactory}
     */
    protected SessionFactory hibernateSessionFactory(SessionFactoryBuilder sessionFactoryBuilder) {
        return sessionFactoryBuilder.build();
    }

    /**
     * Creates the {@link MetadataSources} for the given registry.
     *
     * @param serviceRegistry The registry
     * @return The sources
     */
    @SuppressWarnings("WeakerAccess")
    protected MetadataSources createMetadataSources(@NonNull StandardServiceRegistry serviceRegistry) {
        return new MetadataSources(serviceRegistry);
    }
}
