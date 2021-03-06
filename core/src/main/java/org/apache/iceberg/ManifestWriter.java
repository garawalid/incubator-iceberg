/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Set;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.OutputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writer for manifest files.
 */
public abstract class ManifestWriter implements FileAppender<DataFile> {
  private static final Logger LOG = LoggerFactory.getLogger(ManifestWriter.class);
  static final long UNASSIGNED_SEQ = -1L;

  static ManifestFile copyAppendManifest(ManifestReader reader, OutputFile outputFile, long snapshotId,
                                         SnapshotSummary.Builder summaryBuilder) {
    return copyManifest(reader, outputFile, snapshotId, summaryBuilder, Sets.newHashSet(ManifestEntry.Status.ADDED));
  }

  static ManifestFile copyManifest(ManifestReader reader, OutputFile outputFile, long snapshotId,
                                   SnapshotSummary.Builder summaryBuilder,
                                   Set<ManifestEntry.Status> allowedEntryStatuses) {
    ManifestWriter writer = new V1Writer(reader.spec(), outputFile, snapshotId);
    boolean threw = true;
    try {
      for (ManifestEntry entry : reader.entries()) {
        Preconditions.checkArgument(
            allowedEntryStatuses.contains(entry.status()),
            "Invalid manifest entry status: %s (allowed statuses: %s)",
            entry.status(), allowedEntryStatuses);
        switch (entry.status()) {
          case ADDED:
            summaryBuilder.addedFile(reader.spec(), entry.file());
            writer.add(entry);
            break;
          case EXISTING:
            writer.existing(entry);
            break;
          case DELETED:
            summaryBuilder.deletedFile(reader.spec(), entry.file());
            writer.delete(entry);
            break;
        }
      }

      threw = false;

    } finally {
      try {
        writer.close();
      } catch (IOException e) {
        if (!threw) {
          throw new RuntimeIOException(e, "Failed to close manifest: %s", outputFile);
        }
      }
    }

    return writer.toManifestFile();
  }

  /**
   * Create a new {@link ManifestWriter}.
   * <p>
   * Manifests created by this writer have all entry snapshot IDs set to null.
   * All entries will inherit the snapshot ID that will be assigned to the manifest on commit.
   *
   * @param spec {@link PartitionSpec} used to produce {@link DataFile} partition tuples
   * @param outputFile the destination file location
   * @return a manifest writer
   */
  public static ManifestWriter write(PartitionSpec spec, OutputFile outputFile) {
    // always use a v1 writer for appended manifests because sequence number must be inherited
    return write(1, spec, outputFile, null);
  }

  static ManifestWriter write(int formatVersion, PartitionSpec spec, OutputFile outputFile, Long snapshotId) {
    if (formatVersion == 1) {
      return new V1Writer(spec, outputFile, snapshotId);
    }
    throw new UnsupportedOperationException("Cannot write manifest for table version: " + formatVersion);
  }

  private final OutputFile file;
  private final int specId;
  private final FileAppender<ManifestEntry> writer;
  private final Long snapshotId;
  private final ManifestEntry reused;
  private final PartitionSummary stats;

  private boolean closed = false;
  private int addedFiles = 0;
  private long addedRows = 0L;
  private int existingFiles = 0;
  private long existingRows = 0L;
  private int deletedFiles = 0;
  private long deletedRows = 0L;

  private ManifestWriter(PartitionSpec spec, OutputFile file, Long snapshotId) {
    this.file = file;
    this.specId = spec.specId();
    this.writer = newAppender(FileFormat.AVRO, spec, file);
    this.snapshotId = snapshotId;
    this.reused = new ManifestEntry(spec.partitionType());
    this.stats = new PartitionSummary(spec);
  }

  void addEntry(ManifestEntry entry) {
    switch (entry.status()) {
      case ADDED:
        addedFiles += 1;
        addedRows += entry.file().recordCount();
        break;
      case EXISTING:
        existingFiles += 1;
        existingRows += entry.file().recordCount();
        break;
      case DELETED:
        deletedFiles += 1;
        deletedRows += entry.file().recordCount();
        break;
    }
    stats.update(entry.file().partition());
    writer.add(entry);
  }

  /**
   * Add an added entry for a data file.
   * <p>
   * The entry's snapshot ID will be this manifest's snapshot ID.
   *
   * @param addedFile a data file
   */
  @Override
  public void add(DataFile addedFile) {
    addEntry(reused.wrapAppend(snapshotId, addedFile));
  }

  void add(ManifestEntry entry) {
    addEntry(reused.wrapAppend(snapshotId, entry.file()));
  }

  /**
   * Add an existing entry for a data file.
   *
   * @param existingFile a data file
   * @param fileSnapshotId snapshot ID when the data file was added to the table
   */
  public void existing(DataFile existingFile, long fileSnapshotId) {
    addEntry(reused.wrapExisting(fileSnapshotId, existingFile));
  }

  void existing(ManifestEntry entry) {
    addEntry(reused.wrapExisting(entry.snapshotId(), entry.file()));
  }

  /**
   * Add a delete entry for a data file.
   * <p>
   * The entry's snapshot ID will be this manifest's snapshot ID.
   *
   * @param deletedFile a data file
   */
  public void delete(DataFile deletedFile) {
    addEntry(reused.wrapDelete(snapshotId, deletedFile));
  }

  void delete(ManifestEntry entry) {
    // Use the current Snapshot ID for the delete. It is safe to delete the data file from disk
    // when this Snapshot has been removed or when there are no Snapshots older than this one.
    addEntry(reused.wrapDelete(snapshotId, entry.file()));
  }

  @Override
  public Metrics metrics() {
    return writer.metrics();
  }

  @Override
  public long length() {
    return writer.length();
  }

  public ManifestFile toManifestFile() {
    Preconditions.checkState(closed, "Cannot build ManifestFile, writer is not closed");
    return new GenericManifestFile(file.location(), writer.length(), specId, UNASSIGNED_SEQ, UNASSIGNED_SEQ, snapshotId,
        addedFiles, addedRows, existingFiles, existingRows, deletedFiles, deletedRows, stats.summaries());
  }

  @Override
  public void close() throws IOException {
    this.closed = true;
    writer.close();
  }

  private static <D> FileAppender<D> newAppender(FileFormat format, PartitionSpec spec,
                                                 OutputFile file) {
    Schema manifestSchema = ManifestEntry.getSchema(spec.partitionType());
    try {
      switch (format) {
        case AVRO:
          return Avro.write(file)
              .schema(manifestSchema)
              .named("manifest_entry")
              .meta("schema", SchemaParser.toJson(spec.schema()))
              .meta("partition-spec", PartitionSpecParser.toJsonFields(spec))
              .meta("partition-spec-id", String.valueOf(spec.specId()))
              .overwrite()
              .build();
        default:
          throw new IllegalArgumentException("Unsupported format: " + format);
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to create manifest writer for path: " + file);
    }
  }

  private static class V1Writer extends ManifestWriter {
    V1Writer(PartitionSpec spec, OutputFile file, Long snapshotId) {
      super(spec, file, snapshotId);
    }
  }
}
