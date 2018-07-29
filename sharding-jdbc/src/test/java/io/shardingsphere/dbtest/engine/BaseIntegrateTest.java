/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.dbtest.engine;

import com.google.common.base.Joiner;
import io.shardingsphere.core.api.yaml.YamlMasterSlaveDataSourceFactory;
import io.shardingsphere.core.api.yaml.YamlShardingDataSourceFactory;
import io.shardingsphere.core.constant.DatabaseType;
import io.shardingsphere.core.jdbc.core.datasource.ShardingDataSource;
import io.shardingsphere.core.metadata.datasource.DataSourceMetaData;
import io.shardingsphere.core.metadata.datasource.DataSourceMetaDataBuilder;
import io.shardingsphere.core.metadata.datasource.dialect.H2DataSourceMetaDataBuilder;
import io.shardingsphere.core.metadata.datasource.dialect.MySQLDataSourceMetaDataBuilder;
import io.shardingsphere.core.metadata.datasource.dialect.OracleDataSourceMetaDataBuilder;
import io.shardingsphere.core.metadata.datasource.dialect.PostgreSQLDataSourceMetaDataBuilder;
import io.shardingsphere.core.metadata.datasource.dialect.SQLServerDataSourceMetaDataBuilder;
import io.shardingsphere.core.parsing.cache.ParsingResultCache;
import io.shardingsphere.dbtest.cases.assertion.IntegrateTestCasesLoader;
import io.shardingsphere.dbtest.env.DatabaseTypeEnvironment;
import io.shardingsphere.dbtest.env.EnvironmentPath;
import io.shardingsphere.dbtest.env.IntegrateTestEnvironment;
import io.shardingsphere.dbtest.env.datasource.DataSourceUtil;
import io.shardingsphere.dbtest.env.schema.SchemaEnvironmentManager;
import lombok.AccessLevel;
import lombok.Getter;
import org.junit.After;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.sql.DataSource;
import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;

@RunWith(Parameterized.class)
@Getter(AccessLevel.PROTECTED)
public abstract class BaseIntegrateTest {
    
    private static IntegrateTestEnvironment integrateTestEnvironment = IntegrateTestEnvironment.getInstance();
    
    private static IntegrateTestCasesLoader integrateTestCasesLoader = IntegrateTestCasesLoader.getInstance();
    
    private final String shardingRuleType;
    
    private final DatabaseTypeEnvironment databaseTypeEnvironment;
    
    private final Map<String, DataSource> dataSourceMap;
    
    private final DataSource dataSource;
    
    private final Map<String, DataSource> instanceDataSourceMap;
    
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
    
    public BaseIntegrateTest(final String shardingRuleType, final DatabaseTypeEnvironment databaseTypeEnvironment) throws IOException, JAXBException, SQLException {
        this.shardingRuleType = shardingRuleType;
        this.databaseTypeEnvironment = databaseTypeEnvironment;
        if (databaseTypeEnvironment.isEnabled()) {
            dataSourceMap = createDataSourceMap(shardingRuleType);
            dataSource = createDataSource(dataSourceMap);
            instanceDataSourceMap = createInstanceDataSourceMap();
        } else {
            dataSourceMap = null;
            dataSource = null;
            instanceDataSourceMap = null;
        }
    }
    
    protected String getExpectedDataFile(final String path, final String shardingRuleType, final DatabaseType databaseType, final String expectedDataFile) {
        if (null == expectedDataFile) {
            return null;
        }
        String prefix = path.substring(0, path.lastIndexOf(File.separator));
        String result = Joiner.on("/").join(prefix, "dataset", shardingRuleType, databaseType.toString().toLowerCase(), expectedDataFile);
        if (new File(result).exists()) {
            return result;
        }
        result = Joiner.on("/").join(prefix, "dataset", shardingRuleType, expectedDataFile);
        if (new File(result).exists()) {
            return result;
        }
        return Joiner.on("/").join(prefix, "dataset", expectedDataFile);
    }
    
    private Map<String, DataSource> createDataSourceMap(final String shardingRuleType) throws IOException, JAXBException {
        Collection<String> dataSourceNames = SchemaEnvironmentManager.getDataSourceNames(shardingRuleType);
        Map<String, DataSource> result = new HashMap<>(dataSourceNames.size(), 1);
        for (String each : dataSourceNames) {
            result.put(each, DataSourceUtil.createDataSource(databaseTypeEnvironment.getDatabaseType(), each));
        }
        return result;
    }
    
    private DataSource createDataSource(final Map<String, DataSource> dataSourceMap) throws SQLException, IOException {
        return "masterslave".equals(shardingRuleType)
                ? YamlMasterSlaveDataSourceFactory.createDataSource(dataSourceMap, new File(EnvironmentPath.getShardingRuleResourceFile(shardingRuleType)))
                : YamlShardingDataSourceFactory.createDataSource(dataSourceMap, new File(EnvironmentPath.getShardingRuleResourceFile(shardingRuleType)));
    }
    
    private Map<String, DataSource> createInstanceDataSourceMap() throws SQLException {
        return "masterslave".equals(shardingRuleType) ? dataSourceMap : getShardingInstanceDataSourceMap();
    }
    
    private Map<String, DataSource> getShardingInstanceDataSourceMap() throws SQLException {
        Map<String, DataSource> result = new LinkedHashMap<>();
        Map<String, DataSourceMetaData> dataSourceMetaDataMap = getDataSourceMetaDataMap();
        for (Entry<String, DataSource> entry : dataSourceMap.entrySet()) {
            if (!isExisted(entry.getKey(), result.keySet(), dataSourceMetaDataMap)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
    
    private boolean isExisted(final String dataSourceName, final Collection<String> existedDataSourceNames,
                              final Map<String, DataSourceMetaData> dataSourceMetaDataMap) {
        for (String each : existedDataSourceNames) {
            if (dataSourceMetaDataMap.get(each).isInSameDatabaseInstance(dataSourceMetaDataMap.get(dataSourceName))) {
                return true;
            }
        }
        return false;
    }
    
    private Map<String, DataSourceMetaData> getDataSourceMetaDataMap() throws SQLException {
        Map<String, DataSourceMetaData> result = new LinkedHashMap<>();
        for (Entry<String, DataSource> entry : dataSourceMap.entrySet()) {
            result.put(entry.getKey(), createDataSourceMetaDataParser(databaseTypeEnvironment.getDatabaseType()).build(getDataSourceURL(entry.getValue())));
        }
        return result;
    }
    
    private DataSourceMetaDataBuilder createDataSourceMetaDataParser(final DatabaseType databaseType) {
        switch (databaseType) {
            case H2:
                return new H2DataSourceMetaDataBuilder();
            case MySQL:
                return new MySQLDataSourceMetaDataBuilder();
            case Oracle:
                return new OracleDataSourceMetaDataBuilder();
            case PostgreSQL:
                return new PostgreSQLDataSourceMetaDataBuilder();
            case SQLServer:
                return new SQLServerDataSourceMetaDataBuilder();
            default:
                throw new UnsupportedOperationException(String.format("Cannot support database [%s].", databaseType));
        }
    }
    
    private static String getDataSourceURL(final DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }
    
    protected static void createDatabasesAndTables() {
        try {
            for (String each : integrateTestEnvironment.getShardingRuleTypes()) {
                SchemaEnvironmentManager.dropDatabase(each);
            }
            for (String each : integrateTestEnvironment.getShardingRuleTypes()) {
                SchemaEnvironmentManager.createDatabase(each);
            }
            for (String each : integrateTestEnvironment.getShardingRuleTypes()) {
                SchemaEnvironmentManager.dropTable(each);
            }
            for (String each : integrateTestEnvironment.getShardingRuleTypes()) {
                SchemaEnvironmentManager.createTable(each);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    protected static void dropDatabases() {
        try {
            for (String each : integrateTestEnvironment.getShardingRuleTypes()) {
                SchemaEnvironmentManager.dropDatabase(each);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    @After
    public void tearDown() {
        if (dataSource instanceof ShardingDataSource) {
            ((ShardingDataSource) dataSource).close();
        }
        ParsingResultCache.getInstance().clear();
    }
}
