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
package com.google.exposurenotification.privateanalytics.ingestion;

import com.google.cloud.Timestamp;
import com.google.exposurenotification.privateanalytics.ingestion.IngestionPipeline.DateFilterFn;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.ValidatesRunner;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link IngestionPipeline}.
 */
@RunWith(JUnit4.class)
public class IngestionPipelineTest {

  @Rule
  public TestPipeline pipeline = TestPipeline.create();

  @Test
  @Category(ValidatesRunner.class)
  public void testDateFilter() {
    List<DataShare> dataShares = Arrays.asList(
        DataShare.builder().setId("id1").setCreated(1L).build(),
        DataShare.builder().setId("id2").setCreated(2L).build(),
        DataShare.builder().setId("id3").setCreated(3L).build(),
        DataShare.builder().setId("missing").build()
    );
    PCollection<DataShare> input = pipeline.apply(Create.of(dataShares));

    PCollection<DataShare> output =
        input.apply(
            ParDo.of(new DateFilterFn(StaticValueProvider.of(2L), StaticValueProvider.of(1L))));

    PAssert.that(output).containsInAnyOrder(
        Collections.singletonList(
            DataShare.builder().setId("id2").setCreated(2L)
                .build()));
    pipeline.run().waitUntilFinish();
  }
}
