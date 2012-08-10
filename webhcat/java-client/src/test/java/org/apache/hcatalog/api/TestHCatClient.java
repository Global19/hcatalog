/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hcatalog.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import junit.framework.TestCase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStore;
import org.apache.hadoop.hive.metastore.api.PartitionEventType;
import org.apache.hadoop.hive.ql.io.IgnoreKeyTextOutputFormat;
import org.apache.hadoop.hive.ql.io.RCFileInputFormat;
import org.apache.hadoop.hive.ql.io.RCFileOutputFormat;
import org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hcatalog.cli.SemanticAnalysis.HCatSemanticAnalyzer;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hcatalog.common.HCatException;
import org.apache.hcatalog.data.schema.HCatFieldSchema;
import org.apache.hcatalog.data.schema.HCatFieldSchema.Type;
import org.apache.hcatalog.ExitException;
import org.apache.hcatalog.NoExitSecurityManager;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestHCatClient extends TestCase {
    private static final Logger LOG = LoggerFactory.getLogger(TestHCatClient.class);
    private boolean  isServerRunning = false;
    private static final String msPort  = "20101";
    private HiveConf  hcatConf;
    private Thread  t;
    private SecurityManager securityManager;

    private static class RunMS implements Runnable {

        @Override
        public void run() {
            try {
                HiveMetaStore.main(new String[] { "-v", "-p", msPort });
            } catch (Throwable t) {
                LOG.error("Exiting. Got exception from metastore: ", t);
            }
        }
    }

    @Override
    protected void tearDown() throws Exception {
        LOG.info("Shutting down metastore.");
        System.setSecurityManager(securityManager);
    }

    @Override
    protected void setUp() throws Exception {

        if (isServerRunning) {
            return;
        }

        t = new Thread(new RunMS());
        t.start();
        Thread.sleep(40000);

        isServerRunning = true;

        securityManager = System.getSecurityManager();
        System.setSecurityManager(new NoExitSecurityManager());
        hcatConf = new HiveConf(this.getClass());
        hcatConf.set("hive.metastore.local", "false");
        hcatConf.setVar(HiveConf.ConfVars.METASTOREURIS, "thrift://localhost:"
                + msPort);
        hcatConf.setIntVar(HiveConf.ConfVars.METASTORETHRIFTRETRIES, 3);
        hcatConf.set(HiveConf.ConfVars.SEMANTIC_ANALYZER_HOOK.varname,
                HCatSemanticAnalyzer.class.getName());
        hcatConf.set(HiveConf.ConfVars.PREEXECHOOKS.varname, "");
        hcatConf.set(HiveConf.ConfVars.POSTEXECHOOKS.varname, "");
        hcatConf.set(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY.varname,
                "false");
        System.setProperty(HiveConf.ConfVars.PREEXECHOOKS.varname, " ");
        System.setProperty(HiveConf.ConfVars.POSTEXECHOOKS.varname, " ");
    }

    public void testBasicDDLCommands() throws Exception {
        String db = "testdb";
        String tableOne = "testTable1";
        String tableTwo = "testTable2";
        HCatClient client = HCatClient.create(new Configuration(hcatConf));
        client.dropDatabase(db, true, HCatClient.DROP_DB_MODE.CASCADE);

        HCatCreateDBDesc dbDesc = HCatCreateDBDesc.create(db).ifNotExists(false)
                .build();
        client.createDatabase(dbDesc);
        List<String> dbNames = client.listDatabaseNamesByPattern("*");
        assertTrue(dbNames.contains("default"));
        assertTrue(dbNames.contains(db));

        HCatDatabase testDb = client.getDatabase(db);
        assertTrue(testDb.getComment() == null);
        assertTrue(testDb.getProperties().size() == 0);
        String warehouseDir = System
                .getProperty(ConfVars.METASTOREWAREHOUSE.varname, "/user/hive/warehouse");
        assertTrue(testDb.getLocation().equals(
                "file:" + warehouseDir + "/" + db + ".db"));
        ArrayList<HCatFieldSchema> cols = new ArrayList<HCatFieldSchema>();
        cols.add(new HCatFieldSchema("id", Type.INT, "id comment"));
        cols.add(new HCatFieldSchema("value", Type.STRING, "value comment"));
        HCatCreateTableDesc tableDesc = HCatCreateTableDesc
                .create(db, tableOne, cols).fileFormat("rcfile").build();
        client.createTable(tableDesc);
        HCatTable table1 = client.getTable(db, tableOne);
        assertTrue(table1.getInputFileFormat().equalsIgnoreCase(
                RCFileInputFormat.class.getName()));
        assertTrue(table1.getOutputFileFormat().equalsIgnoreCase(
                RCFileOutputFormat.class.getName()));
        assertTrue(table1.getSerdeLib().equalsIgnoreCase(
                ColumnarSerDe.class.getName()));
        assertTrue(table1.getCols().equals(cols));
        // Since "ifexists" was not set to true, trying to create the same table
        // again
        // will result in an exception.
        try {
            client.createTable(tableDesc);
        } catch (HCatException e) {
            assertTrue(e.getMessage().contains(
                    "AlreadyExistsException while creating table."));
        }

        client.dropTable(db, tableOne, true);
        HCatCreateTableDesc tableDesc2 = HCatCreateTableDesc.create(db,
                tableTwo, cols).build();
        client.createTable(tableDesc2);
        HCatTable table2 = client.getTable(db, tableTwo);
        assertTrue(table2.getInputFileFormat().equalsIgnoreCase(
                TextInputFormat.class.getName()));
        assertTrue(table2.getOutputFileFormat().equalsIgnoreCase(
                IgnoreKeyTextOutputFormat.class.getName()));
        assertTrue(table2.getLocation().equalsIgnoreCase(
                "file:" + warehouseDir + "/" + db + ".db/" + tableTwo));
        client.close();
    }

    public void testPartitionsHCatClientImpl() throws Exception {
        HCatClient client = HCatClient.create(new Configuration(hcatConf));
        String dbName = "ptnDB";
        String tableName = "pageView";
        client.dropDatabase(dbName, true, HCatClient.DROP_DB_MODE.CASCADE);

        HCatCreateDBDesc dbDesc = HCatCreateDBDesc.create(dbName)
                .ifNotExists(true).build();
        client.createDatabase(dbDesc);
        ArrayList<HCatFieldSchema> cols = new ArrayList<HCatFieldSchema>();
        cols.add(new HCatFieldSchema("userid", Type.INT, "id columns"));
        cols.add(new HCatFieldSchema("viewtime", Type.BIGINT,
                "view time columns"));
        cols.add(new HCatFieldSchema("pageurl", Type.STRING, ""));
        cols.add(new HCatFieldSchema("ip", Type.STRING,
                "IP Address of the User"));

        ArrayList<HCatFieldSchema> ptnCols = new ArrayList<HCatFieldSchema>();
        ptnCols.add(new HCatFieldSchema("dt", Type.STRING, "date column"));
        ptnCols.add(new HCatFieldSchema("country", Type.STRING,
                "country column"));
        HCatCreateTableDesc tableDesc = HCatCreateTableDesc
                .create(dbName, tableName, cols).fileFormat("sequencefile")
                .partCols(ptnCols).build();
        client.createTable(tableDesc);

        Map<String, String> firstPtn = new HashMap<String, String>();
        firstPtn.put("dt", "04/30/2012");
        firstPtn.put("country", "usa");
        HCatAddPartitionDesc addPtn = HCatAddPartitionDesc.create(dbName,
                tableName, null, firstPtn).build();
        client.addPartition(addPtn);

        Map<String, String> secondPtn = new HashMap<String, String>();
        secondPtn.put("dt", "04/12/2012");
        secondPtn.put("country", "brazil");
        HCatAddPartitionDesc addPtn2 = HCatAddPartitionDesc.create(dbName,
                tableName, null, secondPtn).build();
        client.addPartition(addPtn2);

        Map<String, String> thirdPtn = new HashMap<String, String>();
        thirdPtn.put("dt", "04/13/2012");
        thirdPtn.put("country", "argetina");
        HCatAddPartitionDesc addPtn3 = HCatAddPartitionDesc.create(dbName,
                tableName, null, thirdPtn).build();
        client.addPartition(addPtn3);

        List<HCatPartition> ptnList = client.listPartitionsByFilter(dbName,
                tableName, null);
        assertTrue(ptnList.size() == 3);

        HCatPartition ptn = client.getPartition(dbName, tableName, firstPtn);
        assertTrue(ptn != null);

        client.dropPartition(dbName, tableName, firstPtn, true);
        ptnList = client.listPartitionsByFilter(dbName,
                tableName, null);
        assertTrue(ptnList.size() == 2);

        List<HCatPartition> ptnListTwo = client.listPartitionsByFilter(dbName,
                tableName, "country = \"argetina\"");
        assertTrue(ptnListTwo.size() == 1);

        client.markPartitionForEvent(dbName, tableName, thirdPtn,
                PartitionEventType.LOAD_DONE);
        boolean isMarked = client.isPartitionMarkedForEvent(dbName, tableName,
                thirdPtn, PartitionEventType.LOAD_DONE);
        assertTrue(isMarked);
        client.close();
    }

    public void testDatabaseLocation() throws Exception{
        HCatClient client = HCatClient.create(new Configuration(hcatConf));
        String dbName = "locationDB";
        client.dropDatabase(dbName, true, HCatClient.DROP_DB_MODE.CASCADE);

        HCatCreateDBDesc dbDesc = HCatCreateDBDesc.create(dbName)
                .ifNotExists(true).location("/tmp/"+dbName).build();
        client.createDatabase(dbDesc);
        HCatDatabase newDB = client.getDatabase(dbName);
        assertTrue(newDB.getLocation().equalsIgnoreCase("file:/tmp/" + dbName));
        client.close();
    }

    public void testCreateTableLike() throws Exception {
        HCatClient client = HCatClient.create(new Configuration(hcatConf));
        String tableName = "tableone";
        String cloneTable = "tabletwo";
        client.dropTable(null, tableName, true);
        client.dropTable(null, cloneTable, true);

        ArrayList<HCatFieldSchema> cols = new ArrayList<HCatFieldSchema>();
        cols.add(new HCatFieldSchema("id", Type.INT, "id columns"));
        cols.add(new HCatFieldSchema("value", Type.STRING, "id columns"));
        HCatCreateTableDesc tableDesc = HCatCreateTableDesc
                .create(null, tableName, cols).fileFormat("rcfile").build();
        client.createTable(tableDesc);
        // create a new table similar to previous one.
        client.createTableLike(null, tableName, cloneTable, true, false, null);
        List<String> tables = client.listTableNamesByPattern(null, "table*");
        assertTrue(tables.size() ==2);
        client.close();
    }

    public void testRenameTable() throws Exception {
        HCatClient client = HCatClient.create(new Configuration(hcatConf));
        String tableName = "temptable";
        String newName = "mytable";
        client.dropTable(null, tableName, true);
        client.dropTable(null, newName, true);
        ArrayList<HCatFieldSchema> cols = new ArrayList<HCatFieldSchema>();
        cols.add(new HCatFieldSchema("id", Type.INT, "id columns"));
        cols.add(new HCatFieldSchema("value", Type.STRING, "id columns"));
        HCatCreateTableDesc tableDesc = HCatCreateTableDesc
                .create(null, tableName, cols).fileFormat("rcfile").build();
        client.createTable(tableDesc);
        client.renameTable(null, tableName,newName);
        try {
            client.getTable(null, tableName);
        } catch(HCatException exp){
            assertTrue(exp.getMessage().contains("NoSuchObjectException while fetching table"));
        }
        HCatTable newTable = client.getTable(null, newName);
        assertTrue(newTable != null);
        assertTrue(newTable.getTableName().equals(newName));
        client.close();
    }

    public void testTransportFailure() throws Exception {
        HCatClient client = HCatClient.create(new Configuration(hcatConf));
        String tableName = "Temptable";
        boolean isExceptionCaught = false;
        Random random = new Random();
        for (int i = 0; i < 80; i++) {
            tableName = tableName + random.nextInt(100);
        }
        ArrayList<HCatFieldSchema> cols = new ArrayList<HCatFieldSchema>();
        cols.add(new HCatFieldSchema("id", Type.INT, "id columns"));
        cols.add(new HCatFieldSchema("value", Type.STRING, "id columns"));
        try {
            HCatCreateTableDesc tableDesc = HCatCreateTableDesc
                    .create(null, tableName, cols).fileFormat("rcfile").build();
            client.createTable(tableDesc);
        } catch (Exception exp) {
            isExceptionCaught = true;
            assertTrue(exp instanceof ConnectionFailureException);
            // The connection was closed, so create a new one.
            client = HCatClient.create(new Configuration(hcatConf));
            String newName = "goodTable";
            client.dropTable(null, newName, true);
            HCatCreateTableDesc tableDesc2 = HCatCreateTableDesc
                    .create(null, newName, cols).fileFormat("rcfile").build();
            client.createTable(tableDesc2);
            HCatTable newTable = client.getTable(null, newName);
            assertTrue(newTable != null);
            assertTrue(newTable.getTableName().equalsIgnoreCase(newName));

        } finally {
            client.close();
            if(isExceptionCaught == false){
                Assert.fail("The expection exception was never thrown.");
            }
        }
    }

    public void testOtherFailure() throws Exception {
        HCatClient client = HCatClient.create(new Configuration(hcatConf));
        String tableName = "Temptable";
        boolean isExceptionCaught = false;
        client.dropTable(null, tableName, true);
        ArrayList<HCatFieldSchema> cols = new ArrayList<HCatFieldSchema>();
        cols.add(new HCatFieldSchema("id", Type.INT, "id columns"));
        cols.add(new HCatFieldSchema("value", Type.STRING, "id columns"));
        try {
            HCatCreateTableDesc tableDesc = HCatCreateTableDesc
                    .create(null, tableName, cols).fileFormat("rcfile").build();
            client.createTable(tableDesc);
            // The DB foo is non-existent.
            client.getTable("foo", tableName);
        } catch (Exception exp) {
            isExceptionCaught = true;
            assertTrue(exp instanceof HCatException);
            String newName = "goodTable";
            client.dropTable(null, newName, true);
            HCatCreateTableDesc tableDesc2 = HCatCreateTableDesc
                    .create(null, newName, cols).fileFormat("rcfile").build();
            client.createTable(tableDesc2);
            HCatTable newTable = client.getTable(null, newName);
            assertTrue(newTable != null);
            assertTrue(newTable.getTableName().equalsIgnoreCase(newName));
        } finally {
            client.close();
            if (isExceptionCaught == false) {
                Assert.fail("The expection exception was never thrown.");
            }
        }
    }
}