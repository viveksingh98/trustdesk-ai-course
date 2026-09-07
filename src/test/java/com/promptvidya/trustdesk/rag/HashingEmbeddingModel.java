package com.promptvidya.trustdesk.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic, offline embedding for tests: a bag-of-words hashed into
 * a fixed-size vector, so texts that share words are similar and the
 * suite never touches a network.
 */
final class HashingEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 64;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var embeddings = new ArrayList<Embedding>();
        var instructions = request.getInstructions();
        for (int index = 0; index < instructions.size(); index++) {
            embeddings.add(new Embedding(embed(instructions.get(index)), index));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        var vector = new float[DIMENSIONS];
        for (var token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        double norm = 0;
        for (var value : vector) {
            norm += value * value;
        }
        var scale = norm == 0 ? 1f : (float) (1 / Math.sqrt(norm));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= scale;
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
