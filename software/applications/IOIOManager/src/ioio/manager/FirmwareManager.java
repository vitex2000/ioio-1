package ioio.manager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

public class FirmwareManager {
	private File appLayerDir_;
	private File activeImagesDir_;
	private String activeBundleName_;
	private Context context_;
	private SharedPreferences preferences_;

	public class ImageFile {
		private File file_;

		public ImageFile(File file) {
			file_ = file;
		}

		public String getName() {
			String name = file_.getName();
			return name.substring(0, name.lastIndexOf('.'));
		}

		public File getFile() {
			return file_;
		}
	}

	public class Bundle {
		private File dir_;

		public Bundle(File dir) {
			dir_ = dir;
		}

		public String getName() {
			return dir_.getName();
		}

		public boolean isActive() {
			return getName().equals(activeBundleName_);
		}

		public ImageFile[] getImages() {
			File[] files = getImageFiles(dir_);
			ImageFile[] result = new ImageFile[files.length];
			for (int i = 0; i < result.length; ++i) {
				result[i] = new ImageFile(files[i]);
			}
			return result;
		}
	}

	FirmwareManager(Context context) throws IOException {
		context_ = context;
		activeImagesDir_ = context.getFilesDir();
		appLayerDir_ = new File(context.getFilesDir().getAbsolutePath()
				+ "/app_layer");
		preferences_ = context_.getSharedPreferences("FirmwareManager", 0);
		activeBundleName_ = preferences_.getString("activeBundleName", null);
		if (!appLayerDir_.exists()) {
			if (!appLayerDir_.mkdir()) {
				throw new IOException("Failed to create directory: "
						+ appLayerDir_.getAbsolutePath());
			}
		} else if (!appLayerDir_.isDirectory()) {
			throw new IllegalStateException(appLayerDir_.getAbsolutePath()
					+ " is not a directory");
		}
	}

	public Bundle addAppBundle(String path) throws IOException {
		File inFile = new File(path);
		String name = inFile.getName();
		name = name.substring(0, name.lastIndexOf('.'));
		File outDir = new File(appLayerDir_.getAbsolutePath() + '/' + name);
		if (outDir.exists()) {
			throw new IOException("Bundle already exists: " + name);
		}
		outDir.mkdirs();
		ZipExtractor.extract(inFile, outDir);
		return new Bundle(outDir);
	}

	public Bundle[] getAppBundles() {
		File[] files = appLayerDir_.listFiles();
		Bundle[] result = new Bundle[files.length];
		for (int i = 0; i < result.length; ++i) {
			result[i] = new Bundle(files[i]);
		}
		return result;
	}

	public void setActiveAppBundle(String name) throws IOException {
		File bundleDir = new File(appLayerDir_.getAbsolutePath() + '/' + name);
		if (!bundleDir.exists() || !bundleDir.isDirectory()) {
			throw new IllegalArgumentException(
					"Bundle does not exist or is not a directory: " + name);
		}
		File[] imageFiles = getImageFiles(bundleDir);
		for (File f : imageFiles) {
			copyFileToFilesDir(f, Context.MODE_WORLD_READABLE);
			try {
				createFingerprint(f.getName());
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}
		activeBundleName_ = name;
		Editor editor = preferences_.edit();
		editor.putString("activeBundleName", name);
		editor.commit();
	}

	public void removeAppBundle(String name) throws IOException {
		File bundleDir = new File(appLayerDir_.getAbsolutePath() + '/' + name);
		if (!bundleDir.exists() || !bundleDir.isDirectory()) {
			throw new IllegalArgumentException(
					"Bundle does not exist or is not a directory: " + name);
		}
		if (name.equals(activeBundleName_)) {
			clearActiveBundle();
		}
		if (!recursiveDelete(bundleDir)) {
			throw new IOException("Recursive delete failed");
		}
	}

	public void clearActiveBundle() throws IOException {
		Editor editor = preferences_.edit();
		editor.putString("activeBundleName", null);
		editor.commit();
		activeBundleName_ = null;
		for (File f : getImageFiles(activeImagesDir_)) {
			if (!f.delete()) {
				throw new IOException("Failed to delete file: "
						+ f.getAbsolutePath());
			}
		}
		for (File f : getFingerprintFiles(activeImagesDir_)) {
			if (!f.delete()) {
				throw new IOException("Failed to delete file: "
						+ f.getAbsolutePath());
			}
		}
	}

	private static File[] getImageFiles(File dir) {
		File[] files = dir.listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String filename) {
				return filename.endsWith(".ioio");
			}
		});
		return files;
	}

	private static File[] getFingerprintFiles(File dir) {
		File[] files = dir.listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String filename) {
				return filename.endsWith(".fp");
			}
		});
		return files;
	}

	private void copyFileToFilesDir(File src, int mode) throws IOException {
		OutputStream out = context_.openFileOutput(src.getName(), mode);
		InputStream in = new FileInputStream(src);
		byte[] buf = new byte[64];
		int numRead;
		while (-1 != (numRead = in.read(buf))) {
			out.write(buf, 0, numRead);
		}
		out.close();
	}

	private static boolean recursiveDelete(File f) {
		if (f.isDirectory()) {
			for (File i : f.listFiles()) {
				if (!recursiveDelete(i)) {
					return false;
				}
			}
			return f.delete();
		} else {
			return f.delete();
		}
	}

	private void createFingerprint(String filename) throws IOException,
			NoSuchAlgorithmException {
		String baseFilename = filename.substring(0, filename.lastIndexOf('.'));
		InputStream in = context_.openFileInput(filename);
		String fingerprintFilename = baseFilename + ".fp";
		OutputStream out = context_.openFileOutput(fingerprintFilename,
				Context.MODE_WORLD_READABLE);
		try {
			MessageDigest digester = MessageDigest.getInstance("MD5");
			byte[] bytes = new byte[1024];
			int byteCount;
			while ((byteCount = in.read(bytes)) > 0) {
				digester.update(bytes, 0, byteCount);
			}
			byte[] digest = digester.digest();
			out.write(digest);
		} finally {
			in.close();
			out.close();
		}
	}

}
