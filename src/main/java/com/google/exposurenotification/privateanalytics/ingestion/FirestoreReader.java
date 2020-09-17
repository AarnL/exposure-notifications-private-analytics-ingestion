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


import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Primitive beam connector for Firestore native specific to ENPA.
 *
 * For a general purpose connector see https://issues.apache.org/jira/browse/BEAM-8376
 */
public class FirestoreReader extends PTransform<PBegin, PCollection<DataShare>> {

  private static final Logger LOG = LoggerFactory.getLogger(FirestoreReader.class);

  private static final Counter invalidDocumentCounter = Metrics
      .counter(FirestoreReader.class, "invalidDocuments");

  @Override
  public PCollection<DataShare> expand(PBegin input) {
    return input
        // XXX: total hack to kick off a DoFn where input doesn't matter (PBegin was giving errors)
        .apply(Create.of(""))
        // TODO(larryjacobs): run partition query to split into cursors, and pass that to ReadFn
        //      apparently the Source idiom is no longer favored?
        //        https://beam.apache.org/documentation/io/developing-io-overview/#sources
        //        https://beam.apache.org/blog/splittable-do-fn/
        // TODO(larryjacobs): reshuffle
        .apply(ParDo.of(new ReadFn()));
  }

  // TODO(larryjacobs): switch to take partitioned query cursors as input
  static class ReadFn extends DoFn<String, DataShare> {

    private Firestore db;

    @StartBundle
    public void startBundle(StartBundleContext context) throws Exception {
      db = initializeFirestore(context.getPipelineOptions().as(IngestionPipelineOptions.class));
    }

    @ProcessElement
    public void processElement(ProcessContext context) throws Exception {
      String metric = context.getPipelineOptions().as(IngestionPipelineOptions.class).getMetric()
          .get();
      for (DataShare ds : readDocumentsFromFirestore(db, metric)) {
        context.output(ds);
      }
    }
  }

  // Initializes and returns a Firestore instance.
  private static Firestore initializeFirestore(IngestionPipelineOptions pipelineOptions)
      throws Exception {
    if (FirebaseApp.getApps().isEmpty()) {
      InputStream serviceAccount = new FileInputStream(
          pipelineOptions.getServiceAccountKey().get());
      GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccount);
      FirebaseOptions options = new FirebaseOptions.Builder()
          .setProjectId(pipelineOptions.getFirebaseProjectId().get())
          .setCredentials(credentials)
          .build();
      FirebaseApp.initializeApp(options);
    }

    return FirestoreClient.getFirestore();
  }

  // Returns all document id's in the collections and subcollections with the given collection id.
  private static List<DataShare> readDocumentsFromFirestore(Firestore db, String collection)
      throws Exception {
    // Create a reference to all collections and subcollections with the given collection id
    Query query = db.collectionGroup(collection);
    // Retrieve query results asynchronously using query.get()
    // TODO(larryjacobs): scalable io connector to Firestore
    ApiFuture<QuerySnapshot> querySnapshot = query.get();
    List<DataShare> docs = new ArrayList<>();
    for (DocumentSnapshot document : querySnapshot.get().getDocuments()) {
      LOG.debug("Fetched document from Firestore: " + document.getId());
      try {
        docs.add(DataShare.from(document));
      } catch (RuntimeException e) {
        LOG.debug("Skipping document: " + document.getId());
        invalidDocumentCounter.inc();
      }
    }
    return docs;
  }
}