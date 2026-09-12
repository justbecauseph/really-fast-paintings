package me.justbecause.fastpaintings.metadata;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class ModMetadataCompatibilityTest {

    public static Path resolveMetadataPath() {
        String customPath = System.getProperty("fabric.mod.json.path");
        if (customPath != null && !customPath.isBlank()) {
            return Paths.get(customPath);
        }
        Path buildPath = Paths.get("build/resources/main/fabric.mod.json");
        if (Files.exists(buildPath)) {
            return buildPath;
        }
        return Paths.get("src/main/resources/fabric.mod.json");
    }

    public static String readMetadataContent() throws IOException {
        Path path = resolveMetadataPath();
        if (!Files.exists(path)) {
            throw new IllegalStateException("fabric.mod.json not found at " + path.toAbsolutePath());
        }
        return Files.readString(path);
    }

    public static String extractJsonField(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalArgumentException("Field '" + fieldName + "' not found in metadata JSON");
    }

    @Test
    @DisplayName("Metadata contains no unexpanded placeholders and has valid identity")
    public void testMetadataIntegrity() throws IOException {
        String json = readMetadataContent();

        // Must not contain any unresolved ${...} placeholders
        Matcher unresolvedMatcher = Pattern.compile("\\$\\{[^}]+\\}").matcher(json);
        if (unresolvedMatcher.find()) {
            fail("Metadata contains unresolved template placeholder: " + unresolvedMatcher.group());
        }

        String id = extractJsonField(json, "id");
        assertEquals("fastpaintings", id, "Mod ID must be 'fastpaintings'");

        String version = extractJsonField(json, "version");
        assertEquals("1.2.0-rc.2", version, "Expanded mod version must match project version 1.2.0-rc.2");
    }

    @Test
    @DisplayName("Minecraft version predicate from processed metadata strictly accepts 26.3-rc.2 and rejects other versions")
    public void testMinecraftVersionPredicate() throws IOException, VersionParsingException {
        String json = readMetadataContent();
        String mcPredicateStr = extractJsonField(json, "minecraft");
        assertEquals("26.3-rc.2", mcPredicateStr, "Expanded minecraft dependency must be '26.3-rc.2'");

        VersionPredicate predicate = VersionPredicate.parse(mcPredicateStr);

        // Target RC version MUST be accepted
        Version targetRc = Version.parse("26.3-rc.2");
        assertTrue(predicate.test(targetRc), "Target 26.3-rc.2 must be accepted");

        // Older versions MUST be rejected
        assertFalse(predicate.test(Version.parse("26.2")), "Baseline 26.2 must be rejected");
        assertFalse(predicate.test(Version.parse("26.1")), "Older 26.1 must be rejected");

        // Other pre-releases / RCs MUST be rejected
        assertFalse(predicate.test(Version.parse("26.3-rc.1")), "Older 26.3-rc.1 must be rejected");
        assertFalse(predicate.test(Version.parse("26.3-rc.3")), "Different 26.3-rc.3 must be rejected");
        assertFalse(predicate.test(Version.parse("26.3-pre.3")), "26.3-pre-3 must be rejected");

        // Final 26.3 release MUST be rejected until port is explicitly updated and tested for final
        assertFalse(predicate.test(Version.parse("26.3")), "Final 26.3 must be rejected by RC-2 specific predicate");

        // Future versions MUST be rejected
        assertFalse(predicate.test(Version.parse("26.4")), "Future 26.4 must be rejected");
    }

    @Test
    @DisplayName("Fabric Loader predicate from processed metadata enforces >=0.19.5")
    public void testLoaderVersionPredicate() throws IOException, VersionParsingException {
        String json = readMetadataContent();
        String loaderPredicateStr = extractJsonField(json, "fabricloader");
        assertEquals(">=0.19.5", loaderPredicateStr, "Expanded fabricloader dependency must be '>=0.19.5'");

        VersionPredicate predicate = VersionPredicate.parse(loaderPredicateStr);

        assertTrue(predicate.test(Version.parse("0.19.5")), "Loader 0.19.5 must be accepted");
        assertTrue(predicate.test(Version.parse("0.19.6")), "Loader 0.19.6 must be accepted");
        assertFalse(predicate.test(Version.parse("0.19.4")), "Loader 0.19.4 must be rejected");
        assertFalse(predicate.test(Version.parse("0.19.3")), "Loader 0.19.3 must be rejected");
        assertFalse(predicate.test(Version.parse("0.18.4")), "Loader 0.18.4 must be rejected");
    }

    @Test
    @DisplayName("Fabric API predicate from processed metadata enforces >=0.160.4")
    public void testFabricApiVersionPredicate() throws IOException, VersionParsingException {
        String json = readMetadataContent();
        String apiPredicateStr = extractJsonField(json, "fabric-api");
        assertEquals(">=0.160.4", apiPredicateStr, "Expanded fabric-api dependency must be '>=0.160.4'");

        VersionPredicate predicate = VersionPredicate.parse(apiPredicateStr);

        assertTrue(predicate.test(Version.parse("0.160.4")), "Fabric API 0.160.4 must be accepted");
        assertTrue(predicate.test(Version.parse("0.160.5")), "Fabric API 0.160.5 must be accepted");
        assertFalse(predicate.test(Version.parse("0.160.3")), "Fabric API 0.160.3 must be rejected");
        assertFalse(predicate.test(Version.parse("0.158.0")), "Fabric API 0.158.0 must be rejected");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running ModMetadataCompatibilityTest against " + resolveMetadataPath().toAbsolutePath());
        ModMetadataCompatibilityTest test = new ModMetadataCompatibilityTest();
        test.testMetadataIntegrity();
        System.out.println("  [PASS] testMetadataIntegrity: valid id, version, zero unresolved placeholders");
        test.testMinecraftVersionPredicate();
        System.out.println("  [PASS] testMinecraftVersionPredicate: strictly accepts 26.3-rc-2, rejects 26.2, 26.3-rc-1, 26.3-rc-3, 26.3 (final), 26.4");
        test.testLoaderVersionPredicate();
        System.out.println("  [PASS] testLoaderVersionPredicate: enforces >=0.19.5 (accepts 0.19.5/0.19.6, rejects 0.19.4/0.19.3/0.18.4)");
        test.testFabricApiVersionPredicate();
        System.out.println("  [PASS] testFabricApiVersionPredicate: enforces >=0.160.4");
        System.out.println("All metadata compatibility checks passed successfully.");
    }
}
