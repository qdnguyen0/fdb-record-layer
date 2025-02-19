/*
 * LuceneOptimizedStoredFieldsFormat.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2023 Apple Inc. and the FoundationDB project authors
 *
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
 */

package com.apple.foundationdb.record.lucene.codec;

import com.apple.foundationdb.record.lucene.LucenePrimaryKeySegmentIndex;
import com.apple.foundationdb.record.lucene.directory.FDBDirectory;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.codecs.StoredFieldsWriter;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * This class provides a Lazy reader implementation to limit the amount of
 * data needed to be read from FDB.
 *
 */
public class LuceneOptimizedStoredFieldsFormat extends StoredFieldsFormat {

    private StoredFieldsFormat storedFieldsFormat;

    LuceneOptimizedStoredFieldsFormat(StoredFieldsFormat storedFieldsFormat) {
        this.storedFieldsFormat = storedFieldsFormat;
    }

    @Override
    public StoredFieldsReader fieldsReader(final Directory directory, final SegmentInfo si, final FieldInfos fn, final IOContext context) throws IOException {
        return new LazyStoredFieldsReader(directory, si, fn, context);
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public StoredFieldsWriter fieldsWriter(final Directory directory, final SegmentInfo si, final IOContext context) throws IOException {
        @Nullable final LucenePrimaryKeySegmentIndex segmentIndex = ((FDBDirectory)FilterDirectory.unwrap(directory)).getPrimaryKeySegmentIndex();
        final StoredFieldsWriter storedFieldsWriter = storedFieldsFormat.fieldsWriter(directory, si, context);
        return segmentIndex == null ? storedFieldsWriter : segmentIndex.wrapFieldsWriter(storedFieldsWriter, si);
    }

    private class LazyStoredFieldsReader extends StoredFieldsReader implements LucenePrimaryKeySegmentIndex.StoredFieldsReaderSegmentInfo {
        private LazyCloseable<StoredFieldsReader> storedFieldsReader;
        private Directory directory;
        private SegmentInfo si;
        private FieldInfos fn;
        private IOContext context;

        public LazyStoredFieldsReader(final Directory directory, final SegmentInfo si, final FieldInfos fn, final IOContext context) {
            this(directory, si, fn, context,
                    LazyCloseable.supply(() -> storedFieldsFormat.fieldsReader(directory, si, fn, context)));
        }

        private LazyStoredFieldsReader(final Directory directory, final SegmentInfo si, final FieldInfos fn, final IOContext context,
                                       LazyCloseable<StoredFieldsReader> storedFieldsReader) {

            this.directory = directory;
            this.si = si;
            this.fn = fn;
            this.context = context;
            this.storedFieldsReader = storedFieldsReader;
        }

        @Override
        public void visitDocument(final int docID, final StoredFieldVisitor visitor) throws IOException {
            storedFieldsReader.get().visitDocument(docID, visitor);
        }

        @Override
        @SuppressWarnings({"PMD.ProperCloneImplementation", "java:S2975"})
        public LazyStoredFieldsReader clone() {
            return new LazyStoredFieldsReader(directory, si, fn, context,
                    LazyCloseable.supply(() -> storedFieldsReader.get().clone()));
        }

        @Override
        public void checkIntegrity() throws IOException {
            if (LuceneOptimizedPostingsFormat.allowCheckDataIntegrity) {
                storedFieldsReader.get().checkIntegrity();
            }
        }

        @Override
        public void close() throws IOException {
            storedFieldsReader.close();
        }

        @Override
        public long ramBytesUsed() {
            return storedFieldsReader.getUnchecked().ramBytesUsed();
        }

        @Override
        public SegmentInfo getSegmentInfo() {
            return si;
        }
    }
}
