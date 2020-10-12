/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.exposurenotification.privateanalytics.ingestion;

import com.google.exposurenotification.privateanalytics.ingestion.DataShare.DataShareMetadata;
import com.google.exposurenotification.privateanalytics.ingestion.DataShare.EncryptedShare;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.abetterinternet.prio.v1.PrioDataSharePacket;
import org.abetterinternet.prio.v1.PrioIngestionHeader;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Sample;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.values.KV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holder of various DoFn's for aspects of serialization
 */
public class SerializationFunctions {

    public static class SerializeDataShareFn extends DoFn<DataShare,
        KV<DataShareMetadata, List<PrioDataSharePacket>>> {
        private static final Logger LOG = LoggerFactory.getLogger(SerializeDataShareFn.class);
        private final Counter dataShareIncluded = Metrics
                .counter(SerializeDataShareFn.class, "dataShareIncluded");

        @ProcessElement
        public void processElement(ProcessContext c) {
            List<EncryptedShare> encryptedDataShares = c.element().getEncryptedDataShares();
            List<PrioDataSharePacket> splitDataShares = new ArrayList<>();
            for (EncryptedShare dataShare : encryptedDataShares) {
                splitDataShares.add(
                    PrioDataSharePacket.newBuilder()
                        .setEncryptionKeyId(dataShare.getEncryptionKeyId())
                        .setEncryptedPayload(
                            ByteBuffer.wrap(
                                dataShare.getEncryptedPayload()))
                        .setRPit(c.element().getRPit())
                        .setUuid(c.element().getUuid())
                        .build()
                );
            }
            dataShareIncluded.inc();
            c.output(KV.of(c.element().getDataShareMetadata(), splitDataShares));
        }
    }

    public static class SerializeIngestionHeaderFn extends DoFn<DataShare, PrioIngestionHeader> {
        private final ValueProvider<Long> startTime;
        private final ValueProvider<Long> duration;
        private final ValueProvider<String> batchUuid;

        public SerializeIngestionHeaderFn(ValueProvider<Long> startTime, ValueProvider<Long> duration) {
            this.startTime = startTime;
            this.duration = duration;
            // TODO: generate batch uuid
            this.batchUuid = StaticValueProvider.of("placeholderUuid");
        }

        @ProcessElement
        public void processElement(ProcessContext c) {
            DataShareMetadata metadata = c.element().getDataShareMetadata();
            c.output(
                PrioIngestionHeader.newBuilder()
                    .setBatchUuid(batchUuid.get())
                    .setName("BatchUuid=" + batchUuid.get())
                    .setBatchStartTime(startTime.get())
                    .setBatchEndTime(startTime.get() + duration.get())
                    .setNumberOfServers(metadata.getNumberOfServers())
                    .setBins(metadata.getBins())
                    .setHammingWeight(metadata.getHammingWeight())
                    .setPrime(metadata.getPrime())
                    .setEpsilon(metadata.getEpsilon())
                    // TODO: implement packet digest
                    .setPacketFileDigest(ByteBuffer.wrap("placeholder".getBytes()))
                    .build()
            );
        }
    }

    public static class ForkByIndexFn extends DoFn<KV<DataShareMetadata, List<PrioDataSharePacket>>,
        KV<DataShareMetadata, PrioDataSharePacket>> {
        private final int index;
        public ForkByIndexFn(int index) {
            this.index = index;
        }

        @ProcessElement
        public void processElement(ProcessContext c) {
            c.output(KV.of(c.element().getKey(), c.element().getValue().get(index)));
        }
    }

    public static void writePrioDataSharePackets(
            IngestionPipelineOptions options,
            String metric,
            PCollection<KV<DataShareMetadata, List<PrioDataSharePacket>>> serializedDataShares) {
        serializedDataShares
                .apply("ForkDataSharesForPHA_metric=" + metric,
                        ParDo.of(new ForkByIndexFn(0)))
                .apply("ExtractPacketPhaFor" + metric,
                        Values.create())
                .apply("WriteToPhaOutput_metric=" + metric,
                        AvroIO.write(PrioDataSharePacket.class)
                                .to(options.getPHAOutput() + "_metric=" + metric)
                                .withSuffix(".avro"));
        serializedDataShares
                .apply("ForkDataSharesForFacilitator_metric=" + metric,
                        ParDo.of(new ForkByIndexFn(1)))
                .apply("ExtractPacketFacilitator_metric=" + metric,
                        Values.create())
                .apply("WriteToFacilitatorOutput_metric=" + metric,
                        AvroIO.write(PrioDataSharePacket.class)
                                .to(options.getFacilitatorOutput() + "_metric=" + metric)
                                .withSuffix(".avro"));
    }

    public static PCollection<String> writeIngestionHeader(
            IngestionPipelineOptions options,
            String metric,
            PCollection<DataShare> dataShares) {
        return dataShares
                .apply("SampleFor" + metric,
                        Sample.any(1))
                .apply("SerializeIngestionHeadersFor" + metric,
                        ParDo.of(
                                new SerializeIngestionHeaderFn(
                                        options.getStartTime(),
                                        options.getDuration())))
                .apply("WriteIngestionHeaderFor" + metric,
                        AvroIO.write(PrioIngestionHeader.class)
                                .to("ingestionHeader_metric=" + metric)
                                .withSuffix(".avro").withOutputFilenames())
                .getPerDestinationOutputFilenames()
                .apply("GetOutputFilenamesFor" + metric, Values.create());
    }

    public static class FilterByMetricFn extends DoFn<DataShare, DataShare> {
        private final String metric;

        public FilterByMetricFn(String metric) {
            this.metric = metric;
        }

        @ProcessElement
        public void processElement(ProcessContext c) {
            if (c.element().getDataShareMetadata().getMetricName().equals(metric)) {
                c.output(c.element());
            }
        }
    }
}