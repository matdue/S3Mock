/*
 *  Copyright 2017-2022 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.adobe.testing.s3mock.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores buckets and their metadata created in S3Mock.
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/creating-buckets-s3.html">API Reference</a>
 */
public class BucketStore {

  private static final Logger LOG = LoggerFactory.getLogger(BucketStore.class);
  private static final Map<String, Object> lockStore = new ConcurrentHashMap<>();
  private static final String BUCKET_META_FILE = "bucketMetadata";
  private final File rootFolder;
  private final boolean retainFilesOnExit;
  private final DateTimeFormatter s3ObjectDateFormat;
  private final ObjectMapper objectMapper;

  public BucketStore(File rootFolder, boolean retainFilesOnExit, List<String> initialBuckets,
      DateTimeFormatter s3ObjectDateFormat, ObjectMapper objectMapper) {
    this.rootFolder = rootFolder;
    this.retainFilesOnExit = retainFilesOnExit;
    this.s3ObjectDateFormat = s3ObjectDateFormat;
    this.objectMapper = objectMapper;
    initialBuckets.forEach(this::createBucket);
  }

  /**
   * Lists all BucketMetadata managed by this store.
   *
   * @return List of all BucketMetadata.
   */
  public List<BucketMetadata> listBuckets() {
    return findBucketPaths()
        .stream()
        .map(path -> path.getFileName().toString())
        .map(this::getBucketMetadata)
        .collect(Collectors.toList());
  }

  /**
   * Retrieves BucketMetadata identified by its name.
   *
   * @param name name of the bucket to be retrieved
   *
   * @return the BucketMetadata or null if not found
   */
  public BucketMetadata getBucketMetadata(String name) {
    try {
      Path metaFilePath = getMetaFilePath(name);
      if (!metaFilePath.toFile().exists()) {
        return null;
      }
      synchronized (lockStore.get(name)) {
        return objectMapper.readValue(metaFilePath.toFile(), BucketMetadata.class);
      }
    } catch (final IOException e) {
      throw new IllegalStateException("Could not read bucket metadata-file " + name, e);
    }
  }

  /**
   * Adds key to a bucket.
   *
   * @param key        the key to add
   * @param bucket name of the bucket to be retrieved
   * @return UUID assigned to key
   */
  public synchronized UUID addToBucket(String key, String bucket) {
    synchronized (lockStore.get(bucket)) {
      BucketMetadata bucketMetadata = getBucketMetadata(bucket);
      UUID uuid = bucketMetadata.addKey(key);
      writeBucket(bucketMetadata);
      return uuid;
    }
  }

  /**
   * Look up keys by prefix in a bucket.
   *
   * @param prefix     the prefix to filter on
   * @param bucket name of the bucket to be retrieved
   * @return List of UUIDs of keys matching the prefix
   */
  public List<UUID> lookupKeysInBucket(String prefix, String bucket) {
    BucketMetadata bucketMetadata = getBucketMetadata(bucket);
    String normalizedPrefix = prefix == null ? "" : prefix;
    return bucketMetadata.getObjects()
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().startsWith(normalizedPrefix))
        .map(Map.Entry::getValue)
        .collect(Collectors.toList());
  }

  /**
   * Removes key from a bucket.
   *
   * @param key        the key to remove
   * @param bucket name of the bucket to be retrieved
   * @return true if key existed and was removed
   */
  public synchronized boolean removeFromBucket(String key, String bucket) {
    synchronized (lockStore.get(bucket)) {
      BucketMetadata bucketMetadata = getBucketMetadata(bucket);
      boolean removed = bucketMetadata.removeKey(key);
      writeBucket(bucketMetadata);
      return removed;
    }
  }

  /**
   * Searches for folders in the rootFolder.
   *
   * @return List of found Folders.
   */
  private List<Path> findBucketPaths() {
    final List<Path> bucketPaths = new ArrayList<>();
    try (final DirectoryStream<Path> stream = Files
        .newDirectoryStream(rootFolder.toPath(), Files::isDirectory)) {
      for (final Path path : stream) {
        bucketPaths.add(path);
      }
    } catch (final IOException e) {
      LOG.error("Could not Iterate over Bucket-Folders", e);
      throw new IllegalStateException("Could not Iterate over Bucket-Folders.", e);
    }

    return bucketPaths;
  }

  /**
   * Creates a new bucket.
   *
   * @param name of the Bucket to be created.
   *
   * @return the newly created Bucket.
   *
   * @throws RuntimeException if the bucket cannot be created or the bucket already exists but is
   *     not a directory.
   */
  public BucketMetadata createBucket(String name) {
    BucketMetadata bucketMetadata = getBucketMetadata(name);
    if (bucketMetadata != null) {
      throw new IllegalStateException("Bucket already exists.");
    }
    lockStore.putIfAbsent(name, new Object());
    synchronized (lockStore.get(name)) {
      final File bucketFolder = createBucketFolder(name);

      BucketMetadata newBucketMetadata = new BucketMetadata();
      newBucketMetadata.setName(name);
      newBucketMetadata.setCreationDate(s3ObjectDateFormat.format(LocalDateTime.now()));
      newBucketMetadata.setPath(bucketFolder.toPath());
      writeBucket(newBucketMetadata);
      return newBucketMetadata;
    }
  }

  private void writeBucket(BucketMetadata bucketMetadata) {
    try {
      File metaFile = getMetaFilePath(bucketMetadata.getName()).toFile();
      if (!retainFilesOnExit) {
        metaFile.deleteOnExit();
      }
      synchronized (lockStore.get(bucketMetadata.getName())) {
        objectMapper.writeValue(metaFile, bucketMetadata);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not write bucket metadata-file", e);
    }
  }

  /**
   * Checks if the specified bucket exists. Amazon S3 buckets are named in a global namespace; use
   * this method to determine if a specified bucket name already exists, and therefore can't be used
   * to create a new bucket.
   *
   * @param name of the bucket to check for existence
   *
   * @return true if Bucket exists
   */
  public Boolean doesBucketExist(String name) {
    return getBucketMetadata(name) != null;
  }

  /**
   * Checks if the specified bucket exists and if it is empty.
   *
   * @param name of the bucket to check for existence
   *
   * @return true if Bucket is empty
   */
  public boolean isBucketEmpty(String name) {
    BucketMetadata bucketMetadata = getBucketMetadata(name);
    if (bucketMetadata != null) {
      return bucketMetadata.getObjects().isEmpty();
    } else {
      throw new IllegalStateException("Requested Bucket does not exist: " + name);
    }
  }

  /**
   * Deletes a Bucket and all of its contents.
   * TODO: in S3, all objects within a bucket must be deleted before deleting a bucket!
   *
   * @param name of the bucket to be deleted.
   *
   * @return true if deletion succeeded.
   *
   */
  public boolean deleteBucket(String name) {
    try {
      synchronized (lockStore.get(name)) {
        BucketMetadata bucketMetadata = getBucketMetadata(name);
        if (bucketMetadata != null && bucketMetadata.getObjects().isEmpty()) {
          //TODO: this currently does not work, since we store objects below their prefixes, which
          // are not deleted when deleting the object, leaving empty directories in the S3Mock
          // filesystem should be: return Files.deleteIfExists(bucket.getPath())
          FileUtils.deleteDirectory(bucketMetadata.getPath().toFile());
          lockStore.remove(name);
          return true;
        } else {
          return false;
        }
      }
    } catch (final IOException e) {
      throw new IllegalStateException("Can't create bucket directory!", e);
    }
  }

  private Path getBucketFolderPath(String name) {
    return Paths.get(rootFolder.getPath(), name);
  }

  private File createBucketFolder(String name) {
    try {
      File bucketFolder = getBucketFolderPath(name).toFile();
      FileUtils.forceMkdir(bucketFolder);
      if (!retainFilesOnExit) {
        bucketFolder.deleteOnExit();
      }
      return bucketFolder;
    } catch (final IOException e) {
      throw new IllegalStateException("Can't create bucket directory!", e);
    }
  }

  private Path getMetaFilePath(String name) {
    return Paths.get(getBucketFolderPath(name).toString(), BUCKET_META_FILE);
  }
}
