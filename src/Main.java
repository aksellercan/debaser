import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static java.lang.System.exit;

public class Main {

    private static class IgnoredFiles {
        private static final List<String> ignoreSystemFilesList = List.of(
                //ignore MacOS generated file
                ".DS_Store"
        );
        private static final List<String> ignoreAPIFilesList = List.of(
                //ignore thumbnails folder in user dir
                ".thumbnails",
                //ignore compression folder in user dir
                ".compression"
        );
        ;

        public static boolean isInIgnoreSystemFilesList(String filename) {
            return ignoreSystemFilesList.stream().anyMatch(file -> file.equals(filename));
        }

        public static boolean isInIgnoreAPIFilesList(String filename) {
            return ignoreAPIFilesList.stream().anyMatch(file -> file.equals(filename));
        }

        public static boolean isIgnoredFile(String filename) {
            return isInIgnoreSystemFilesList(filename) || isInIgnoreAPIFilesList(filename);
        }
    }

    public static String decodeBase32StringNoPadding(String base32String) {
        return new String(Base64.getUrlDecoder().decode(base32String.getBytes()));
    }

    /**
     * Decode BASE32 encoded file/folder names.
     *
     * @param base32String BASE32 encoded file/folder name
     * @return decoded BASE32 string split by ":". Split index 0 is file/folder ID, 1 is file/folder name and 2 is for userID
     */
    public static String[] decodedBase32SplitArray(String base32String) {
        return decodeBase32StringNoPadding(base32String).split(":");
    }

    public static boolean isEncodedStringUserDirectory(String encodedString) {
        try {
            long checkLastIndexType /* should be long */ = Long.parseLong(decodedBase32SplitArray(encodedString)[2]);
            //if successful parse MOST likely its not user directory
            System.out.println("Most likely NOT user directory");
            return false;
        } catch (/* throws */ NumberFormatException e) {
            System.out.println("Most likely user directory");
            return true;
        }
    }

    public static boolean isBase32Decodable(String name) {
        try {
            String tryDecoding = decodeBase32StringNoPadding(name);
            long tryIdParse = Long.parseLong(tryDecoding.split(":")[0]);
            System.out.printf("trial of id parsing %s from %s\n", tryIdParse, tryDecoding);
            return !tryDecoding.isEmpty();
        } catch (Exception e) {
            System.out.printf("Failed to parse concluding as not BASE32 for string %s Ex. %s\n", name, e.getMessage());
            return false;
        }
    }

    public static List<Path> getListOfFoldersFromPath(String path) throws IOException {
        List<Path> fileList;
        try (Stream<Path> stream = Files.list(Path.of(path))) {
            fileList = stream.toList();
        }
        return fileList;
    }

    private static void handleFileCheck(File currentFile) throws IOException {
        if (!isBase32Decodable(currentFile.getName())) {
            //if its base32 decodable check if its in db
            // we can also decode Base32 and get id to search by ID index could be more performant
            System.out.println("skip");
            return;
        }
        short newFileEntry = createNewFileEntry(currentFile);
        System.out.printf("New file entry status %d\n", newFileEntry);
    }

    public static Path returnPathIfItExists(Path checkExists) {
        if (!Files.exists(checkExists)) {
            System.out.printf("%s does not exist at path %s%n",
                    (Files.isRegularFile(checkExists) ? "File" : "Folder"), checkExists);
            return null;
        }
        return checkExists;
    }

    private static short createNewFileEntry(File currentFile) throws IOException {
        System.out.printf("-> FILE %s\n", currentFile.getPath());
        String encodedFileName = decodedBase32SplitArray(currentFile.getName())[1];
        Files.move(currentFile.toPath(), Path.of(currentFile.getParentFile().getPath() + File.separator + encodedFileName), StandardCopyOption.REPLACE_EXISTING);
        return 0;
    }

    private static File handleFolderCheck(File currentFolder) throws IOException {
        String newFolderName = decodedBase32SplitArray(currentFolder.getName())[1];
        Path target = Path.of(currentFolder.getParentFile().getPath() + File.separator + newFolderName);
        if (returnPathIfItExists(target) != null) return target.toFile();
        System.out.printf("mutated path %s\n", target);
        Path result = Files.move(currentFolder.toPath(), target);
        System.out.printf("Created folder NAME %s\n", newFolderName);
        return result.toFile();
    }

    public static boolean scanDirectory(Path startingPath) {
        try {
            List<Path> folders = getListOfFoldersFromPath(String.valueOf(startingPath));
            for (Path files : folders) {
                System.out.printf("Currently on %s: %s\n", (Files.isRegularFile(files) ? "FILE" : "FOLDER"), files.getFileName());
                if (IgnoredFiles.isIgnoredFile(files.getFileName().toString())) {
                    System.out.printf("Skip ignorable file or folder %s\n", files.getFileName().toString());
                    continue;
                }
                if (Files.isRegularFile(files)) {
                    handleFileCheck(files.toFile());
                    continue;
                }
                if (!isBase32Decodable(files.getFileName().toString())) {
                    System.out.println("undecodable skipping");
                    if (Files.isDirectory(files)) {
                        if (!scanDirectory(files))
                            throw new RuntimeException("Failed to enter folder");
                    }
                    continue;
                }
                File createdFolder = handleFolderCheck(files.toFile());
                if (!scanDirectory(createdFolder.toPath()))
                    throw new RuntimeException("Failed to enter created folder");
            }
            return true;
        } catch (Exception e) {
            System.out.printf("Exception occurred %s\n", e.getMessage());
            return false;
        }
    }

    public static void afterCleanup(Path path) throws IOException {
        if (!isBase32Decodable(path.getFileName().toString())) {
            System.out.println("skip");
            return;
        }
        String actualUserFolderName = decodedBase32SplitArray(path.getFileName().toString())[1];
        Path target = Path.of(path.toFile().getParentFile().getPath() + File.separator + actualUserFolderName);
        Path result = Files.move(path, target);
        System.out.printf("Created folder NAME %s\nPath %s\n", actualUserFolderName, result);
    }

    public static boolean wrapper(Path path) {
        try {
            scanDirectory(path);
//            afterCleanup(path);
            return true;
        } catch (Exception e) {
            System.out.printf("Exception occurred %s\n", e.getMessage());
            return false;
        }
    }

    public static String askForInputIfEmpty() {
        String input;
        while (true) {
            try {
                System.out.print("=> ");
                input = new BufferedReader(new InputStreamReader(System.in)).readLine();
                boolean continueCheck = true;
                if (input.isBlank()) {
                    System.out.println("Invalid input");
                    continueCheck = false;
                }
                if (continueCheck) {
                    break;
                }
            } catch (Exception e) {
                System.out.printf("Failed to process input %s\n", e.getMessage());
                return null;
            }
        }
        return input;
    }

    public static void main(String[] args) {
        String path;
        if (args.length == 0) {
            path = askForInputIfEmpty();
            if (path == null) {
                System.out.println("No input given");
                exit(1);
            }
        } else {
            path = args[0];
        }
        Path startingPath = Path.of(path);
        boolean success = wrapper(startingPath);
        System.out.printf("success: %b\n", success);
    }
}
