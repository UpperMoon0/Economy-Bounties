package com.nstut.economybounties.data;

import com.nstut.economybounties.api.PostedBountyStore;
import com.nstut.economybounties.api.PostedBountyView;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;

/** Atomic JSON file store suitable for a per-world server data directory. */
public final class JsonFilePostedBountyStore implements PostedBountyStore {
    private final Path path;
    private final PostedBountyJsonCodec codec;

    public JsonFilePostedBountyStore(Path path) { this(path, new PostedBountyJsonCodec()); }
    public JsonFilePostedBountyStore(Path path, PostedBountyJsonCodec codec) {
        this.path = path.toAbsolutePath().normalize();
        this.codec = codec;
    }

    @Override
    public synchronized Collection<PostedBountyView> load() {
        if (!Files.isRegularFile(path)) return List.of();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return codec.decode(reader);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Failed to load posted bounties from " + path, error);
        }
    }

    @Override
    public synchronized void save(Collection<PostedBountyView> bounties) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, codec.encode(bounties), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to save posted bounties to " + path, error);
        }
    }
}
