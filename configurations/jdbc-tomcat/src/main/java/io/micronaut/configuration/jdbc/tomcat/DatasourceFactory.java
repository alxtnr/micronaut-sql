/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.configuration.jdbc.tomcat;

import io.micronaut.configuration.jdbc.tomcat.metadata.TomcatDataSourcePoolMetadata;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Requires;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates a tomcat data source for each configuration bean.
 *
 * @author James Kleeh
 * @author Christian Oestreich
 * @since 1.0
 */
@Factory
public class DatasourceFactory implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DatasourceFactory.class);
    private List<org.apache.tomcat.jdbc.pool.DataSource> dataSources = new ArrayList<>(2);

    /**
     * @param datasourceConfiguration A {@link DatasourceConfiguration}
     * @return An Apache Tomcat {@link DataSource}
     */
    @EachBean(DatasourceConfiguration.class)
    public DataSource dataSource(DatasourceConfiguration datasourceConfiguration) {
        org.apache.tomcat.jdbc.pool.DataSource ds = new org.apache.tomcat.jdbc.pool.DataSource(datasourceConfiguration);
        dataSources.add(ds);
        return ds;
    }

    @EachBean(DataSource.class)
    @Requires(beans = {DataSource.class, DatasourceConfiguration.class})
    public TomcatDataSourcePoolMetadata tomcatPoolDataSourceMetadataProvider(
            @Parameter String dataSourceName,
            DataSource dataSource) {

        LOG.info("\n\n\n\nCreating datasource for " + dataSourceName + "\n\n\n\n");
        if (dataSource instanceof org.apache.tomcat.jdbc.pool.DataSource) {
            return new TomcatDataSourcePoolMetadata((org.apache.tomcat.jdbc.pool.DataSource) dataSource, dataSourceName);
        } else if ((dataSource instanceof DelegatingDataSource && ((DelegatingDataSource) dataSource).getTargetDataSource() instanceof org.apache.tomcat.jdbc.pool.DataSource)) {
            return new TomcatDataSourcePoolMetadata((org.apache.tomcat.jdbc.pool.DataSource) ((DelegatingDataSource) dataSource).getTargetDataSource(), dataSourceName);
        }
        return null;
    }

    @Override
    @PreDestroy
    public void close() {
        for (org.apache.tomcat.jdbc.pool.DataSource dataSource : dataSources) {
            try {
                dataSource.close();
            } catch (Exception e) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Error closing data source [" + dataSource + "]: " + e.getMessage(), e);
                }
            }
        }
    }

}
