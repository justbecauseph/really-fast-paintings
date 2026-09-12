package me.justbecause.fastpaintings.metadata;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ModMetadataCompatibilityTest {

    @Test
    @DisplayName("Minecraft version predicate strictly accepts 26.3-rc-2 and rejects all other versions")
    public void testMinecraftVersionPredicate() throws VersionParsingException {
        // Explicit RC target predicate
        VersionPredicate predicate = VersionPredicate.parse("26.3-rc-2");

        // Target RC version MUST be accepted
        Version targetRc = Version.parse("26.3-rc-2");
        assertTrue(predicate.test(targetRc), "Target 26.3-rc-2 must be accepted");

        // Unsupported versions MUST be rejected
        Version baseline26_2 = Version.parse("26.2");
        assertFalse(predicate.test(baseline26_2), "Baseline 26.2 must be rejected by 26.3-rc-2 predicate");

        Version olderRc = Version.parse("26.3-rc-1");
        assertFalse(predicate.test(olderRc), "Older 26.3-rc-1 must be rejected by 26.3-rc-2 predicate");

        Version preRelease = Version.parse("26.3-pre-3");
        assertFalse(predicate.test(preRelease), "26.3-pre-3 must be rejected by 26.3-rc-2 predicate");

        Version olderMinor = Version.parse("26.1");
        assertFalse(predicate.test(olderMinor), "26.1 must be rejected by 26.3-rc-2 predicate");

        Version futureRelease = Version.parse("26.4");
        assertFalse(predicate.test(futureRelease), "26.4 must be rejected by 26.3-rc-2 predicate");
    }

    @Test
    @DisplayName("Fabric Loader predicate enforces minimum loader 0.19.3")
    public void testLoaderVersionPredicate() throws VersionParsingException {
        VersionPredicate predicate = VersionPredicate.parse(">=0.19.3");

        assertTrue(predicate.test(Version.parse("0.19.3")), "Loader 0.19.3 must be accepted");
        assertTrue(predicate.test(Version.parse("0.19.4")), "Loader 0.19.4 must be accepted");
        assertFalse(predicate.test(Version.parse("0.19.2")), "Loader 0.19.2 must be rejected");
        assertFalse(predicate.test(Version.parse("0.18.4")), "Loader 0.18.4 must be rejected");
    }

    @Test
    @DisplayName("Fabric API predicate enforces minimum 0.160.4")
    public void testFabricApiVersionPredicate() throws VersionParsingException {
        VersionPredicate predicate = VersionPredicate.parse(">=0.160.4");

        assertTrue(predicate.test(Version.parse("0.160.4")), "Fabric API 0.160.4 must be accepted");
        assertTrue(predicate.test(Version.parse("0.160.5")), "Fabric API 0.160.5 must be accepted");
        assertFalse(predicate.test(Version.parse("0.158.0")), "Fabric API 0.158.0 must be rejected");
    }
}
