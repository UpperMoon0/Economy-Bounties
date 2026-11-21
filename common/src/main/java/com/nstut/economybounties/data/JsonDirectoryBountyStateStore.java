package com.nstut.economybounties.data;

import com.nstut.economybounties.api.BountyStateStore;
import com.nstut.economybounties.api.PlayerBountyStateSnapshot;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/** Atomic per-player JSON persistence for generated bounty state. */
public final class JsonDirectoryBountyStateStore implements BountyStateStore {
    private final Path directory;
    private final PlayerBountyStateJsonCodec codec;

    public JsonDirectoryBountyStateStore(Path directory) { this(directory, new PlayerBountyStateJsonCodec()); }
    public JsonDirectoryBountyStateStore(Path directory, PlayerBountyStateJsonCodec codec) {
        this.directory = directory.toAbsolutePath().normalize();
        this.codec = codec;
    }

    @Override
    public synchronized Optional<PlayerBountyStateSnapshot> load(UUID playerId) {
        Path path = path(playerId);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            PlayerBountyStateSnapshot snapshot = codec.decode(reader);
            if (!playerId.equals(snapshot.playerId())) throw new IllegalStateException("Player state file contains the wrong UUID");
            return Optional.of(snapshot);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Failed to load bounty state " + path, error);
        }
    }

    @Override
    public synchronized void save(PlayerBountyStateSnapshot snapshot) {
        Path path = path(snapshot.playerId());
        try {
            Files.createDirectories(directory);
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, codec.encode(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to save bounty state " + path, error);
        }
    }

    private Path path(UUID playerId) { return directory.resolve(playerId + ".json"); }
}
