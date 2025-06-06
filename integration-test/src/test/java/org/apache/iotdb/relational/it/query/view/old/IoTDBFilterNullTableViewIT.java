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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.relational.it.query.view.old;

import org.apache.iotdb.it.env.EnvFactory;
import org.apache.iotdb.it.framework.IoTDBTestRunner;
import org.apache.iotdb.itbase.category.TableClusterIT;
import org.apache.iotdb.itbase.category.TableLocalStandaloneIT;
import org.apache.iotdb.itbase.env.BaseEnv;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.apache.iotdb.db.it.utils.TestUtils.defaultFormatDataTime;
import static org.apache.iotdb.db.it.utils.TestUtils.prepareData;
import static org.apache.iotdb.db.it.utils.TestUtils.prepareTableData;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(IoTDBTestRunner.class)
@Category({TableLocalStandaloneIT.class, TableClusterIT.class})
public class IoTDBFilterNullTableViewIT {
  private static final String DATABASE_NAME = "test";

  private static final String[] createSqls =
      new String[] {
        "CREATE DATABASE root.testNullFilter",
        "CREATE TIMESERIES root.testNullFilter.d1.s1 WITH DATATYPE=INT32, ENCODING=PLAIN",
        "CREATE TIMESERIES root.testNullFilter.d1.s2 WITH DATATYPE=BOOLEAN, ENCODING=PLAIN",
        "CREATE TIMESERIES root.testNullFilter.d1.s3 WITH DATATYPE=DOUBLE, ENCODING=PLAIN"
      };

  private static final String[] insertSqls =
      new String[] {
        "INSERT INTO root.testNullFilter.d1(timestamp,s2,s3) " + "values(1, false, 11.1)",
        "INSERT INTO root.testNullFilter.d1(timestamp,s1,s2) " + "values(2, 22, true)",
        "INSERT INTO root.testNullFilter.d1(timestamp,s1,s3) " + "values(3, 23, 33.3)",
      };

  private static final String[] createTableViewSqls =
      new String[] {
        "CREATE DATABASE " + DATABASE_NAME,
        "USE " + DATABASE_NAME,
        "CREATE VIEW testNullFilter(device STRING TAG, s1 INT32 FIELD, s2 BOOLEAN FIELD, s3 DOUBLE FIELD) as root.testNullFilter.**",
      };

  @BeforeClass
  public static void setUp() throws Exception {
    EnvFactory.getEnv().initClusterEnvironment();
    prepareData(createSqls);
    prepareData(insertSqls);
    prepareTableData(createTableViewSqls);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    EnvFactory.getEnv().cleanClusterEnvironment();
  }

  @Test
  public void nullFilterTest() {
    String[] retArray =
        new String[] {
          defaultFormatDataTime(1) + ",null,false,11.1",
          defaultFormatDataTime(2) + ",22,true,null",
          defaultFormatDataTime(3) + ",23,null,33.3"
        };
    try (Connection connectionIsNull =
            EnvFactory.getEnv().getConnection(BaseEnv.TABLE_SQL_DIALECT);
        Statement statement = connectionIsNull.createStatement()) {
      statement.execute("USE " + DATABASE_NAME);
      int count = 0;

      ResultSet resultSet = statement.executeQuery("select * from testNullFilter where s1 is null");
      while (resultSet.next()) {
        String ans =
            resultSet.getString("time")
                + ","
                + resultSet.getString("s1")
                + ","
                + resultSet.getString("s2")
                + ","
                + resultSet.getString("s3");
        assertEquals(retArray[count], ans);
        count++;
      }

      resultSet = statement.executeQuery("select * from testNullFilter where s1 is not null");
      while (resultSet.next()) {
        String ans =
            resultSet.getString("time")
                + ","
                + resultSet.getString("s1")
                + ","
                + resultSet.getString("s2")
                + ","
                + resultSet.getString("s3");
        assertEquals(retArray[count], ans);
        count++;
      }
      assertEquals(retArray.length, count);

    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }
}
