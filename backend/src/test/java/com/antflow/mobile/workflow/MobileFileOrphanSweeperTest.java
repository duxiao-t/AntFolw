package com.antflow.mobile.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/** 删除是不可逆的：默认 dry-run，真删时也只碰"没有行引用 + 够老"的对象。 */
class MobileFileOrphanSweeperTest {

    @Test
    void dryRunDeletesNothingEvenForRealCandidates() {
        FakeStorage storage = storageWithThreeObjects();
        MobileFileProperties properties = new MobileFileProperties();

        sweeper(storage, properties).sweep();

        assertThat(storage.deleted).isEmpty();
    }

    @Test
    void deletesOnlyUnreferencedObjectsOlderThanTheGracePeriod() {
        FakeStorage storage = storageWithThreeObjects();
        MobileFileProperties properties = new MobileFileProperties();
        properties.setOrphanSweepDelete(true);

        sweeper(storage, properties).sweep();

        // 有行引用（哪怕很老）与刚写进去的都不动：前者是在用的文件，后者可能还在写。
        assertThat(storage.deleted).containsExactly("old-orphan.png");
    }

    @Test
    void aStorageThatCannotBeListedIsSkippedInsteadOfFailingTheSchedule() {
        FileStorage withoutListing = new FakeStorage(List.of()) {
            @Override
            public List<StoredKey> list() {
                throw new UnsupportedOperationException("storage does not support listing");
            }
        };

        sweeper(withoutListing, new MobileFileProperties()).sweep();
    }

    private static FakeStorage storageWithThreeObjects() {
        OffsetDateTime now = OffsetDateTime.now();
        return new FakeStorage(List.of(
            new FileStorage.StoredKey("referenced.png", now.minusHours(48)),
            new FileStorage.StoredKey("fresh-orphan.png", now.minusHours(1)),
            new FileStorage.StoredKey("old-orphan.png", now.minusHours(48))));
    }

    private static MobileFileOrphanSweeper sweeper(FileStorage storage, MobileFileProperties properties) {
        MobileFileMapper mapper = Mockito.mock(MobileFileMapper.class);
        Mockito.when(mapper.selectAllStorageKeys()).thenReturn(List.of("referenced.png"));
        return new MobileFileOrphanSweeper(storage, mapper, properties);
    }

    private static class FakeStorage implements FileStorage {
        private final List<StoredKey> objects;
        final List<String> deleted = new ArrayList<>();

        FakeStorage(List<StoredKey> objects) {
            this.objects = objects;
        }

        @Override
        public List<StoredKey> list() {
            return objects;
        }

        @Override
        public StoredObject put(String storageKey, InputStream content, long size, String contentType)
            throws IOException {
            return new StoredObject(storageKey, size);
        }

        @Override
        public Resource get(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String storageKey) {
            return true;
        }

        @Override
        public void delete(String storageKey) {
            deleted.add(storageKey);
        }
    }
}
