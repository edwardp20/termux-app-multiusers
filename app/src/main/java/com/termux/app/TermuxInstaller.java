package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    private static void log(String message) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/storage/emulated/10/debug.log", true);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US);
            fw.write(sdf.format(new java.util.Date()) + " " + message + "\n");
            fw.close();
        } catch (Exception e) {
            // 静默失败，不影响主逻辑
        }
    }

    private static void log(String tag, String message) {
        log("[" + tag + "] " + message);
    }

    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        log("TermuxInstaller", "=== setupBootstrapIfNeeded() START ===");
        log("TermuxInstaller", "TERMUX_PREFIX_DIR_PATH: " + TERMUX_PREFIX_DIR_PATH);

        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        log("TermuxInstaller", "Checking if Termux files directory is accessible...");
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;
        log("TermuxInstaller", "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);

        // 解除限制
        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            ...
        }*/

        if (!isFilesDirectoryAccessible) {
            log("TermuxInstaller", "ERROR: Files directory not accessible!");
            // ... 错误处理代码保持不变 ...
            return;
        }

        // 检查 prefix 目录是否存在
        log("TermuxInstaller", "Checking if prefix directory exists: " + TERMUX_PREFIX_DIR_PATH);
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            log("TermuxInstaller", "Prefix directory exists.");

            // 检查是否真的安装好了（bin 目录必须存在）
            File binDir = new File(TERMUX_PREFIX_DIR_PATH, "bin");
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty() || !binDir.exists()) {
                log("TermuxInstaller", "Prefix is empty or missing bin/. Will reinstall.");
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" is incomplete. Reinstalling.");
            } else {
                log("TermuxInstaller", "Prefix is valid (bin/ exists). Skipping install.");
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            log("TermuxInstaller", "A file exists at prefix path (not a directory).");
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        log("TermuxInstaller", "Starting bootstrap installation process...");

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    log("TermuxInstaller", "Thread started. Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // 删除 staging 目录
                    log("TermuxInstaller", "Deleting staging directory: " + TERMUX_STAGING_PREFIX_DIR_PATH);
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        log("TermuxInstaller", "ERROR: Failed to delete staging directory: " + Error.getErrorMarkdownString(error));
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // 删除 prefix 目录
                    log("TermuxInstaller", "Deleting prefix directory: " + TERMUX_PREFIX_DIR_PATH);
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        log("TermuxInstaller", "ERROR: Failed to delete prefix directory: " + Error.getErrorMarkdownString(error));
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // 创建 staging 目录
                    log("TermuxInstaller", "Creating staging directory...");
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        log("TermuxInstaller", "ERROR: Failed to create staging directory: " + Error.getErrorMarkdownString(error));
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // 创建 prefix 目录
                    log("TermuxInstaller", "Creating prefix directory...");
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        log("TermuxInstaller", "ERROR: Failed to create prefix directory: " + Error.getErrorMarkdownString(error));
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    log("TermuxInstaller", "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");
                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    log("TermuxInstaller", "Calling loadZipBytes()...");
                    final byte[] zipBytes = loadZipBytes();
                    log("TermuxInstaller", "loadZipBytes() returned " + (zipBytes == null ? "NULL" : zipBytes.length + " bytes"));

                    if (zipBytes == null || zipBytes.length == 0) {
                        log("TermuxInstaller", "ERROR: zipBytes is null or empty!");
                        throw new RuntimeException("loadZipBytes() returned empty data");
                    }

                    log("TermuxInstaller", "Creating ZipInputStream...");
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        int entryCount = 0;
                        int fileCount = 0;
                        int symlinkCount = 0;

                        log("TermuxInstaller", "Starting to iterate zip entries...");

                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            entryCount++;
                            String entryName = zipEntry.getName();
                            log("TermuxInstaller", "Entry " + entryCount + ": " + entryName);

                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                log("TermuxInstaller", "Found SYMLINKS.txt, processing symlinks...");
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2) {
                                        log("TermuxInstaller", "Malformed symlink line: " + line);
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    }
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));
                                    symlinkCount++;
                                    log("TermuxInstaller", "  Symlink " + symlinkCount + ": " + oldPath + " ← " + newPath);

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        log("TermuxInstaller", "ERROR: ensureDirectoryExists failed for " + newPath);
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                                log("TermuxInstaller", "SYMLINKS.txt processed, " + symlinkCount + " symlinks found.");
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                log("TermuxInstaller", "  " + (isDirectory ? "DIR" : "FILE") + ": " + zipEntryName);

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    log("TermuxInstaller", "ERROR: ensureDirectoryExists failed for " + targetFile.getAbsolutePath());
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1) {
                                            outStream.write(buffer, 0, readBytes);
                                        }
                                    }
                                    fileCount++;
                                    log("TermuxInstaller", "    Written: " + targetFile.getAbsolutePath() + " (" + targetFile.length() + " bytes)");

                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                        log("TermuxInstaller", "    chmod 0700: " + targetFile.getAbsolutePath());
                                    }
                                }
                            }
                        }

                        log("TermuxInstaller", "=== Zip iteration complete ===");
                        log("TermuxInstaller", "Total entries: " + entryCount + ", Files extracted: " + fileCount + ", Symlinks: " + symlinkCount);
                    }

                    if (symlinks.isEmpty()) {
                        log("TermuxInstaller", "ERROR: No SYMLINKS.txt encountered!");
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    }

                    log("TermuxInstaller", "Creating " + symlinks.size() + " symlinks...");
                    for (Pair<String, String> symlink : symlinks) {
                        log("TermuxInstaller", "  Creating symlink: " + symlink.second + " -> " + symlink.first);
                        Os.symlink(symlink.first, symlink.second);
                    }

                    log("TermuxInstaller", "Moving staging to prefix: " + TERMUX_STAGING_PREFIX_DIR + " -> " + TERMUX_PREFIX_DIR);
                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        log("TermuxInstaller", "ERROR: renameTo() failed!");
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    log("TermuxInstaller", "SUCCESS: Bootstrap packages installed successfully!");
                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // 重新生成环境文件
                    log("TermuxInstaller", "Writing environment file...");
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    log("TermuxInstaller", "Calling whenDone.run()...");
                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    log("TermuxInstaller", "EXCEPTION: " + e.getMessage());
                    e.printStackTrace();
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    // 其他方法（showBootstrapErrorDialog, sendBootstrapCrashReportNotification, setupStorageSymlinks, ensureDirectoryExists）保持不变 ...

    //use assets
    /*public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }*/

    public static byte[] loadZipBytes() {
        log("TermuxInstaller", "=== loadZipBytes() START ===");

        try {
            log("TermuxInstaller", "Reading assets/bootstrap-aarch64.zip");
            InputStream is = TermuxInstaller.class.getResourceAsStream("/assets/bootstrap-aarch64.zip");

            if (is == null) {
                log("TermuxInstaller", "ERROR: bootstrap-aarch64.zip NOT FOUND in assets");
                throw new RuntimeException("bootstrap-aarch64.zip not found in assets");
            }

            log("TermuxInstaller", "bootstrap-aarch64.zip found, reading...");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            int totalBytes = 0;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
                totalBytes += len;
            }
            is.close();

            log("TermuxInstaller", "SUCCESS: read " + totalBytes + " bytes from assets");
            return baos.toByteArray();

        } catch (Exception e) {
            log("TermuxInstaller", "ERROR in loadZipBytes: " + e.getMessage());
            throw new RuntimeException("Failed to load bootstrap from assets", e);
        }
    }

    /*
    public static native byte[] getZip();
    */
        public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);
    
        sendBootstrapCrashReportNotification(activity, message);
    
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }
    
    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";
    
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }
    
    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";
    
        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");
    
        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;
    
                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }
    
                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");
    
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());
    
                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());
    
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());
    
                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());
    
                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());
    
                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());
    
                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());
    
                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());
    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }
    
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }
    
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }
    
                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }
    
    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }
}