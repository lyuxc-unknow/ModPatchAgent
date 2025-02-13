package me.lyuxc.agent;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * ModPatcherAgent 实现对 {@link #PATCHES_DIR} 目录下的每个子文件夹进行修补，
 * 将该子文件夹内所有 .class 文件添加到对应的 jar 文件中（存放在 mods 目录下）。
 * 修改后：仅记录每个子文件夹的处理状态，状态 JSON 存放于 {@link #PATCHES_DIR} 文件夹下。
 */
public class ModPatcherAgent {
    private static final String PATCHES_DIR = "patches";
    private static int NEW_FILE_COUNT = 0;
    private static final Path STATUS_FILE = Paths.get(PATCHES_DIR, "patcher_status.json");
    private static final Gson gson = new Gson();

    // 当通过 -javaagent 启动时调用
    public static void premain(String agentArgs, Instrumentation inst) {
        patchMods();
        if (NEW_FILE_COUNT > 0) {
            System.out.println("Some mods have been patched, please restart the game");
            System.out.println("有模组被修改了，请重启游戏加载！");
            System.exit(0);
        }
    }

    // 当通过动态 attach 加载时调用
    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("you should use -javaagent to patch mods");
    }

    /**
     * 遍历 {@link #PATCHES_DIR} 目录下的每个子文件夹，如果该子文件夹未被处理过，
     * 则将该文件夹下所有 .class 文件复制到目标 jar（mods/<子文件夹名>.jar）中，
     * 并在{@link #STATUS_FILE} 中记录该文件夹已经处理。
     */
    private static void patchMods() {
        // 加载已有状态（以子文件夹为 key）
        Map<String, Boolean> patchStatusMap = loadStatusMap();

        Path patchesDir = Paths.get(PATCHES_DIR);
        if (!Files.exists(patchesDir) || !Files.isDirectory(patchesDir)) {
            System.err.println("directory " + PATCHES_DIR + " does not exist or is not a directory.");
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(patchesDir)) {
            for (Path folder : stream) {
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                String folderKey = folder.getFileName().toString();
                if (patchStatusMap.getOrDefault(folderKey, false)) {
                    System.out.println("Subfolder " + folderKey + " already processed, skipping.");
                    continue;
                }
                // 构造目标 jar 文件路径：mods/<folderName>.jar
                Path jarPath = Paths.get("mods", folderKey + ".jar");
                Files.createDirectories(jarPath.getParent());
                if (!Files.exists(jarPath)) {
                    Files.createFile(jarPath);
                }
                Map<String, String> env = new HashMap<>();
                env.put("create", "true");
                URI uri = URI.create("jar:" + jarPath.toUri());
                try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
                    // 遍历当前子文件夹下的所有 .class 文件
                    Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (file.toString().endsWith(".class")) {
                                // 计算相对于当前文件夹的路径
                                Path relPath = folder.relativize(file);
                                String entryName = relPath.toString().replace("\\", "/");
//                                System.out.println("add " + entryName + " to " + jarPath);
                                Path target = fs.getPath(entryName);
                                if (target.getParent() != null) {
                                    Files.createDirectories(target.getParent());
                                }
                                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                                NEW_FILE_COUNT++;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    System.err.println("Error processing jar file " + jarPath + ": " + e.getMessage());
                    e.fillInStackTrace();
                }
                // 记录该子文件夹已处理
                patchStatusMap.put(folderKey, true);
            }
        } catch (IOException e) {
            System.err.println("Error accessing directory "+ PATCHES_DIR +": " + e.getMessage());
            e.fillInStackTrace();
        }
        // 保存更新后的状态文件
        saveStatusMap(patchStatusMap);
    }

    /**
     * 从 STATUS_FILE 中加载状态配置。如果文件不存在则返回空 Map。
     */
    private static Map<String, Boolean> loadStatusMap() {
        if (Files.exists(STATUS_FILE)) {
            try {
                String json = Files.readString(STATUS_FILE);
                Map<String, Boolean> map = gson.fromJson(json, new TypeToken<Map<String, Boolean>>() {}.getType());
                if (map != null) {
                    return map;
                }
            } catch (IOException e) {
                System.err.println("Error reading " + STATUS_FILE + ": " + e.getMessage());
                e.fillInStackTrace();
            }
        }
        return new HashMap<>();
    }

    /**
     * 将状态 Map 保存到 STATUS_FILE 文件中。
     */
    private static void saveStatusMap(Map<String, Boolean> map) {
        try {
            String json = gson.toJson(map);
            Files.writeString(STATUS_FILE, json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Error writing " + STATUS_FILE + ": " + e.getMessage());
            e.fillInStackTrace();
        }
    }
}
