package ch.tkuhn.nanopub.server.storage;

import java.util.Arrays;
import java.util.Optional;

public enum CollectionTypeEnum {
    Journal("journal"),
    Peers("peers"),
    Nanopubs("nanopubs"),
    PackagedFile("packagedFile");

    private String propertyName;

    CollectionTypeEnum(String propertyName) {
        this.propertyName = propertyName;
    }
    public static Optional<CollectionTypeEnum> get(String propertyName) {
        return Arrays.stream(CollectionTypeEnum.values())
                .filter(env -> env.propertyName.equals(propertyName))
                .findFirst();
    }
}
