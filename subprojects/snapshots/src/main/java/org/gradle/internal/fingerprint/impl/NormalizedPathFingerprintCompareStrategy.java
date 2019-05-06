/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.fingerprint.impl;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.MultimapBuilder;
import org.gradle.internal.change.ChangeVisitor;
import org.gradle.internal.change.DefaultFileChange;
import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.fingerprint.FingerprintCompareStrategy;
import org.gradle.internal.hash.Hasher;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Compares by normalized path (relative/name only) and file contents. Order does not matter.
 */
public class NormalizedPathFingerprintCompareStrategy extends AbstractFingerprintCompareStrategy {
    public static final FingerprintCompareStrategy INSTANCE = new NormalizedPathFingerprintCompareStrategy();

    private NormalizedPathFingerprintCompareStrategy() {}

    /**
     * Determines changes by:
     *
     * <ul>
     *     <li>Determining which {@link FileSystemLocationFingerprint}s are only in the previous or current fingerprint collection.</li>
     *     <li>
     *         For those only in the previous fingerprint collection it checks if some entry with the same normalized path is in the current collection.
     *         If it is, file is reported as modified, if not as removed.
     *     </li>
     *     <li>Finally, if {@code shouldIncludeAdded} is {@code true}, the remaining fingerprints which are only in the current collection are reported as added.</li>
     * </ul>
     */
    @Override
    protected boolean doVisitChangesSince(
        ChangeVisitor visitor,
        Map<String, FileSystemLocationFingerprint> currentFingerprints,
        Map<String, FileSystemLocationFingerprint> previousFingerprints,
        String propertyTitle,
        boolean shouldIncludeAdded
    ) {
        ListMultimap<FileSystemLocationFingerprint, FilePathWithType> missingPreviousFiles = getMissingPreviousFingerprints(previousFingerprints);
        ListMultimap<String, FilePathWithType> addedFilesByNormalizedPath = getAddedFilesByNormalizedPath(currentFingerprints, missingPreviousFiles);

        for (Entry<FileSystemLocationFingerprint, FilePathWithType> entry : missingPreviousFiles.entries()) {
            if (wasModifiedOrRemovedAndMessageCountSaturated(visitor, propertyTitle, addedFilesByNormalizedPath, entry)) {
                return false;
            }
        }

        if (shouldIncludeAdded) {
            for (Entry<String, FilePathWithType> entry : addedFilesByNormalizedPath.entries()) {
                if (wasAddedAndMessageCountSaturated(visitor, propertyTitle, entry)) {
                    return false;
                }
            }
        }
        return true;
    }

    private ListMultimap<FileSystemLocationFingerprint, FilePathWithType> getMissingPreviousFingerprints(
        Map<String, FileSystemLocationFingerprint> previousFingerprints
    ) {
        ListMultimap<FileSystemLocationFingerprint, FilePathWithType> results = MultimapBuilder
            .treeKeys()
            .linkedListValues()
            .build();
        for (Entry<String, FileSystemLocationFingerprint> entry : previousFingerprints.entrySet()) {
            String absolutePath = entry.getKey();
            FileSystemLocationFingerprint previousFingerprint = entry.getValue();
            FileType previousFingerprintType = previousFingerprint.getType();
            results.put(previousFingerprint, new FilePathWithType(absolutePath, previousFingerprintType));
        }
        return results;
    }

    private ListMultimap<String, FilePathWithType> getAddedFilesByNormalizedPath(
        Map<String, FileSystemLocationFingerprint> currentFingerprints,
        ListMultimap<FileSystemLocationFingerprint, FilePathWithType> missingPreviousFiles
    ) {
        ListMultimap<String, FilePathWithType> results = MultimapBuilder
            .linkedHashKeys()
            .linkedListValues()
            .build();
        for (Entry<String, FileSystemLocationFingerprint> entry : currentFingerprints.entrySet()) {
            String currentAbsolutePath = entry.getKey();
            FileSystemLocationFingerprint currentFingerprint = entry.getValue();
            boolean wasAccountedFor = accountFor(missingPreviousFiles, currentAbsolutePath, currentFingerprint);
            if (!wasAccountedFor) {
                FileType fingerprintType = currentFingerprint.getType();
                results.put(currentFingerprint.getNormalizedPath(), new FilePathWithType(currentAbsolutePath, fingerprintType));
            }
        }
        return results;
    }

    private boolean accountFor(
        ListMultimap<FileSystemLocationFingerprint, FilePathWithType> missingPreviousFingerprints,
        String absolutePath,
        FileSystemLocationFingerprint currentFingerprint
    ) {
        List<FilePathWithType> currentMissingFingerprints = missingPreviousFingerprints.get(currentFingerprint);
        for (Iterator<FilePathWithType> iterator = currentMissingFingerprints.iterator(); iterator.hasNext(); ) {
            if (absolutePath.equals(iterator.next().getAbsolutePath())) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private boolean wasModifiedOrRemovedAndMessageCountSaturated(
        ChangeVisitor visitor,
        String propertyTitle,
        ListMultimap<String, FilePathWithType> addedFilesByNormalizedPath,
        Entry<FileSystemLocationFingerprint, FilePathWithType> missingPreviousFingerprintEntry
    ) {
        FileSystemLocationFingerprint previousFingerprint = missingPreviousFingerprintEntry.getKey();
        String normalizedPath = previousFingerprint.getNormalizedPath();
        FileType previousFingerprintType = previousFingerprint.getType();

        // There might be multiple files with the same normalized path, here we choose one of them
        List<FilePathWithType> addedFilesForNormalizedPath = addedFilesByNormalizedPath.get(normalizedPath);
        if (!addedFilesForNormalizedPath.isEmpty()) {
            return wasModifiedAndMessageCountSaturated(visitor, propertyTitle, previousFingerprintType, normalizedPath, addedFilesForNormalizedPath);
        } else {
            return wasRemovedAndMessageCountSaturated(visitor, propertyTitle, missingPreviousFingerprintEntry, normalizedPath);
        }
    }

    private boolean wasModifiedAndMessageCountSaturated(
        ChangeVisitor visitor,
        String propertyTitle,
        FileType previousFingerprintType,
        String normalizedPath,
        List<FilePathWithType> addedFilesForNormalizedPath
    ) {
        FilePathWithType addedFile = addedFilesForNormalizedPath.remove(0);
        String absolutePath = addedFile.getAbsolutePath();
        FileType fileType = addedFile.getFileType();
        DefaultFileChange modified = DefaultFileChange.modified(absolutePath, propertyTitle, previousFingerprintType, fileType, normalizedPath);
        return !visitor.visitChange(modified);
    }

    private boolean wasRemovedAndMessageCountSaturated(
        ChangeVisitor visitor,
        String propertyTitle,
        Entry<FileSystemLocationFingerprint, FilePathWithType> missingPreviousFingerprintEntry,
        String normalizedPath
    ) {
        FilePathWithType removedFile = missingPreviousFingerprintEntry.getValue();
        String absolutePath = removedFile.getAbsolutePath();
        FileType fileType = removedFile.getFileType();
        DefaultFileChange removed = DefaultFileChange.removed(absolutePath, propertyTitle, fileType, normalizedPath);
        return !visitor.visitChange(removed);
    }

    private boolean wasAddedAndMessageCountSaturated(
        ChangeVisitor visitor,
        String propertyTitle,
        Entry<String, FilePathWithType> addedFilesByNormalizedPathEntries
    ) {
        FilePathWithType addedFile = addedFilesByNormalizedPathEntries.getValue();
        String absolutePath = addedFile.getAbsolutePath();
        FileType fileType = addedFile.getFileType();
        String normalizedPath = addedFilesByNormalizedPathEntries.getKey();
        DefaultFileChange added = DefaultFileChange.added(absolutePath, propertyTitle, fileType, normalizedPath);
        return !visitor.visitChange(added);
    }

    @Override
    public void appendToHasher(Hasher hasher, Collection<FileSystemLocationFingerprint> fingerprints) {
        appendSortedToHasher(hasher, fingerprints);
    }

    public static void appendSortedToHasher(Hasher hasher, Collection<FileSystemLocationFingerprint> fingerprints) {
        List<FileSystemLocationFingerprint> sortedFingerprints = Lists.newArrayList(fingerprints);
        Collections.sort(sortedFingerprints);
        for (FileSystemLocationFingerprint normalizedSnapshot : sortedFingerprints) {
            normalizedSnapshot.appendToHasher(hasher);
        }
    }
}
