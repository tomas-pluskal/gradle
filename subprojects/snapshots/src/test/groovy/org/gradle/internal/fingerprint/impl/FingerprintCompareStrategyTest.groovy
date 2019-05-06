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

package org.gradle.internal.fingerprint.impl

import com.google.common.collect.Iterables
import org.gradle.internal.change.CollectingChangeVisitor
import org.gradle.internal.change.DefaultFileChange
import org.gradle.internal.file.FileType
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint
import org.gradle.internal.fingerprint.FingerprintCompareStrategy
import org.gradle.internal.hash.HashCode
import spock.lang.Specification
import spock.lang.Unroll

import static AbstractFingerprintCompareStrategy.compareTrivialFingerprints

class FingerprintCompareStrategyTest extends Specification {

    private static final ABSOLUTE = AbsolutePathFingerprintCompareStrategy.INSTANCE
    private static final NORMALIZED = NormalizedPathFingerprintCompareStrategy.INSTANCE
    private static final IGNORED_PATH = IgnoredPathCompareStrategy.INSTANCE

    @Unroll
    def "empty snapshots (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            [:],
            [:]
        ) as List == []

        where:
        strategy     | shouldIncludeAdded
        NORMALIZED   | true
        NORMALIZED   | false
        IGNORED_PATH | true
        IGNORED_PATH | false
        ABSOLUTE     | true
        ABSOLUTE     | false
    }

    @Unroll
    def "trivial addition (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one")],
            [:]
        ) as List == results

        where:
        strategy     | shouldIncludeAdded | results
        NORMALIZED   | true         | [added("one": "one")]
        NORMALIZED   | false        | []
        IGNORED_PATH | true         | [added("one": "one")]
        IGNORED_PATH | false        | []
        ABSOLUTE     | true         | [added("one": "one")]
        ABSOLUTE     | false        | []
    }

    @Unroll
    def "non-trivial addition (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one"), "two": fingerprint("two")],
            ["one": fingerprint("one")]
        ) == results

        where:
        strategy   | shouldIncludeAdded | results
        NORMALIZED | true         | [added("two": "two")]
        NORMALIZED | false        | []
    }

    @Unroll
    def "non-trivial addition with absolute paths (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one"), "two": fingerprint("two")],
            ["one": fingerprint("one")]
        ) == results

        where:
        strategy | shouldIncludeAdded | results
        ABSOLUTE | true         | [added("two")]
        ABSOLUTE | false        | []
    }

    @Unroll
    def "trivial removal (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            [:],
            ["one": fingerprint("one")]
        ) as List == [removed("one": "one")]

        where:
        strategy   | shouldIncludeAdded
        NORMALIZED | true
        NORMALIZED | false
        ABSOLUTE   | true
        ABSOLUTE   | false
    }

    @Unroll
    def "non-trivial removal (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one")],
            ["one": fingerprint("one"), "two": fingerprint("two")]
        ) == [removed("two": "two")]

        where:
        strategy   | shouldIncludeAdded
        NORMALIZED | true
        NORMALIZED | false
    }

    @Unroll
    def "non-trivial removal with absolute paths (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one")],
            ["one": fingerprint("one"), "two": fingerprint("two")]
        ) == [removed("two")]

        where:
        strategy | shouldIncludeAdded
        ABSOLUTE | true
        ABSOLUTE | false
    }

    @Unroll
    def "non-trivial modification (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one"), "two": fingerprint("two", 0x9876cafe)],
            ["one": fingerprint("one"), "two": fingerprint("two", 0xface1234)]
        ) == [modified("two": "two", FileType.RegularFile, FileType.RegularFile)]

        where:
        strategy   | shouldIncludeAdded
        NORMALIZED | true
        NORMALIZED | false
    }

    @Unroll
    def "non-trivial modification with re-ordering and same normalized paths (UNORDERED, include added: #shouldIncludeAdded)"() {
        expect:
        changes(NORMALIZED, shouldIncludeAdded,
            ["two": fingerprint("", 0x9876cafe), "one": fingerprint("")],
            ["one": fingerprint(""), "two": fingerprint("", 0xface1234)]
        ) == [modified("two": "", FileType.RegularFile, FileType.RegularFile)]

        where:
        shouldIncludeAdded << [true, false]
    }

    @Unroll
    def "non-trivial modification with absolute paths (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one"), "two": fingerprint("two", 0x9876cafe)],
            ["one": fingerprint("one"), "two": fingerprint("two", 0xface1234)]
        ) == [modified("two", FileType.RegularFile, FileType.RegularFile)]

        where:
        strategy | shouldIncludeAdded
        ABSOLUTE | true
        ABSOLUTE | false
    }

    @Unroll
    def "trivial replacement (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["two": fingerprint("two")],
            ["one": fingerprint("one")]
        ) as List == results

        where:
        strategy   | shouldIncludeAdded | results
        NORMALIZED | true         | [removed("one": "one"), added("two": "two")]
        NORMALIZED | false        | [removed("one": "one")]
    }

    @Unroll
    def "non-trivial replacement (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one"), "two": fingerprint("two"), "four": fingerprint("four")],
            ["one": fingerprint("one"), "three": fingerprint("three"), "four": fingerprint("four")]
        ) == results

        where:
        strategy   | shouldIncludeAdded | results
        NORMALIZED | true         | [removed("three": "three"), added("two": "two")]
        NORMALIZED | false        | [removed("three": "three")]
    }

    @Unroll
    def "non-trivial replacement with absolute paths (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one"), "two": fingerprint("two"), "four": fingerprint("four")],
            ["one": fingerprint("one"), "three": fingerprint("three"), "four": fingerprint("four")]
        ) == results

        where:
        strategy | shouldIncludeAdded | results
        ABSOLUTE | true         | [added("two"), removed("three")]
        ABSOLUTE | false        | [removed("three")]
    }

    @Unroll
    def "reordering (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one"), "two": fingerprint("two"), "three": fingerprint("three")],
            ["one": fingerprint("one"), "three": fingerprint("three"), "two": fingerprint("two")]
        ) == results

        where:
        strategy   | shouldIncludeAdded | results
        NORMALIZED | true         | []
        NORMALIZED | false        | []
    }

    @Unroll
    def "reordering with absolute paths (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one": fingerprint("one"), "two": fingerprint("two"), "three": fingerprint("three")],
            ["one": fingerprint("one"), "three": fingerprint("three"), "two": fingerprint("two")]
        ) == results

        where:
        strategy | shouldIncludeAdded | results
        ABSOLUTE | true         | []
        ABSOLUTE | false        | []
    }

    @Unroll
    def "handling duplicates (#strategy, include added: #shouldIncludeAdded)"() {
        expect:
        changes(strategy, shouldIncludeAdded,
            ["one-1": fingerprint("one"), "one-2": fingerprint("one"), "two": fingerprint("two")],
            ["one-1": fingerprint("one"), "one-2": fingerprint("one"), "two": fingerprint("two")]
        ) == []

        where:
        strategy   | shouldIncludeAdded
        NORMALIZED | true
        NORMALIZED | false
    }

    @Unroll
    def "mix file contents with same name (#strategy, include added: #shouldIncludeAdded)"() {
        def changes = changes(strategy, shouldIncludeAdded,
            ["a/absolute": fingerprint("absolute", 2),
             "a/relative": fingerprint("a/relative", 3),

             "b/absolute": fingerprint("absolute", 0),
             "b/relative": fingerprint("b/relative", 1)],

            ["a/absolute": fingerprint("absolute", 0),
             "a/relative": fingerprint("a/relative", 1),

             "b/absolute": fingerprint("absolute", 2),
             "b/relative": fingerprint("b/relative", 3)]
        )
        expect:
        changes*.toString().toSet() == results*.toString().toSet()

        where:
        strategy   | shouldIncludeAdded | results
        NORMALIZED | true         | [modified("a/relative"), modified("b/relative"), modified("a/absolute"), modified("b/absolute")]
        NORMALIZED | false        | [modified("a/relative"), modified("b/relative"), modified("a/absolute"), modified("b/absolute")]
    }

    @Unroll
    def "change file content to other content with same name (#strategy, include added: #shouldIncludeAdded)"() {
        def changes = changes(strategy, shouldIncludeAdded,
            ["a/absolute": fingerprint("absolute", 2),
             "a/relative": fingerprint("a/relative", 3),

             "b/absolute": fingerprint("absolute", 2),    // unchanged
             "b/relative": fingerprint("b/relative", 3)], // unchanged

            ["a/absolute": fingerprint("absolute", 0),
             "a/relative": fingerprint("a/relative", 1),

             "b/absolute": fingerprint("absolute", 2),
             "b/relative": fingerprint("b/relative", 3)]
        )
        expect:
        changes*.toString().toSet() == results*.toString().toSet()

        where:
        strategy   | shouldIncludeAdded | results
        NORMALIZED | true         | [modified("a/relative"), modified("a/absolute")]
        NORMALIZED | false        | [modified("a/relative"), modified("a/absolute")]
    }

    @Unroll
    def "too many elements not handled by trivial comparison (#current.size() current vs #previous.size() previous)"() {
        expect:
        compareTrivialFingerprints(new CollectingChangeVisitor(), current, previous, "test", true) == null
        compareTrivialFingerprints(new CollectingChangeVisitor(), current, previous, "test", false) == null

        where:
        current                                                | previous
        ["one": fingerprint("one")]                            | ["one": fingerprint("one"), "two": fingerprint("two")]
        ["one": fingerprint("one"), "two": fingerprint("two")] | ["one": fingerprint("one")]
    }

    def changes(FingerprintCompareStrategy strategy, boolean shouldIncludeAdded, Map<String, FileSystemLocationFingerprint> current, Map<String, FileSystemLocationFingerprint> previous) {
        def visitor = new CollectingChangeVisitor()
        strategy.visitChangesSince(visitor, current, previous, "test", shouldIncludeAdded)
        visitor.getChanges().toList()
    }

    def fingerprint(String normalizedPath, def hashCode = 0x1234abcd) {
        return new DefaultFileSystemLocationFingerprint(normalizedPath, FileType.RegularFile, HashCode.fromInt((int) hashCode))
    }

    def added(String path) {
        added((path): path)
    }

    def added(Map<String, String> entry) {
        def singleEntry = Iterables.getOnlyElement(entry.entrySet())
        DefaultFileChange.added(singleEntry.key, "test", FileType.RegularFile, singleEntry.value)
    }

    def removed(String path) {
        removed((path): path)
    }

    def removed(Map<String, String> entry) {
        def singleEntry = Iterables.getOnlyElement(entry.entrySet())
        DefaultFileChange.removed(singleEntry.key, "test", FileType.RegularFile, singleEntry.value)
    }

    def modified(String path, FileType previous = FileType.RegularFile, FileType current = FileType.RegularFile) {
        modified((path): path, previous, current)
    }

    def modified(Map<String, String> paths, FileType previous = FileType.RegularFile, FileType current = FileType.RegularFile) {
        def singleEntry = Iterables.getOnlyElement(paths.entrySet())
        DefaultFileChange.modified(singleEntry.key, "test", previous, current, singleEntry.value)
    }
}
