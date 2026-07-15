package cn.nabr.chatwithchat.data.memory.vector;

import android.content.Context;

import io.objectbox.BoxStore;
import io.objectbox.query.ObjectWithScore;
import io.objectbox.query.Query;

import java.io.File;
import java.util.List;

public final class MemoryVectorReleaseCompatibilityProbe {
    private MemoryVectorReleaseCompatibilityProbe() {
    }

    public static void writeQueryAndClose(
            Context context,
            File directory,
            String chunkId,
            float[] embedding
    ) {
        BoxStore store = openStore(context, directory);
        try {
            MemoryVectorChunkEntity entity = new MemoryVectorChunkEntity(
                    0L,
                    chunkId,
                    null,
                    "MEMORY.md",
                    0,
                    null,
                    "16 KB release compatibility canary",
                    null,
                    null,
                    null,
                    null,
                    0L,
                    0L,
                    "",
                    "",
                    0L,
                    "",
                    "",
                    "",
                    512,
                    "",
                    1,
                    embedding
            );
            store.boxFor(MemoryVectorChunkEntity.class).put(entity);
            assertNearestChunk(store, chunkId, embedding);
        } finally {
            store.close();
        }
    }

    public static void queryAndClose(
            Context context,
            File directory,
            String chunkId,
            float[] embedding
    ) {
        BoxStore store = openStore(context, directory);
        try {
            assertNearestChunk(store, chunkId, embedding);
        } finally {
            store.close();
        }
    }

    private static BoxStore openStore(Context context, File directory) {
        return MyObjectBox.builder()
                .androidContext(context)
                .directory(directory)
                .build();
    }

    private static void assertNearestChunk(BoxStore store, String chunkId, float[] embedding) {
        Query<MemoryVectorChunkEntity> query = store.boxFor(MemoryVectorChunkEntity.class)
                .query(MemoryVectorChunkEntity_.embedding.nearestNeighbors(embedding, 1))
                .build();
        try {
            List<ObjectWithScore<MemoryVectorChunkEntity>> matches = query.findWithScores();
            if (matches.size() != 1 || !chunkId.equals(matches.get(0).get().getChunkId())) {
                throw new IllegalStateException("ObjectBox HNSW query did not return the persisted canary");
            }
        } finally {
            query.close();
        }
    }
}
