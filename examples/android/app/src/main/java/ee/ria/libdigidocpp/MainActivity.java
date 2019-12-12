package ee.ria.libdigidocpp;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {

	static private final int REQUEST_READWRITE_STORAGE = 1;
	static private String cache;

	static {
		System.loadLibrary("c++_shared");
		System.loadLibrary("digidoc_java");
	}

	static private void saveToFile(InputStream in, String path) throws IOException {
		FileOutputStream out = new FileOutputStream(path);
		byte[] buffer = new byte[10240];
		int count;
		while ((count = in.read(buffer)) != -1) {
			out.write(buffer, 0, count);
		}
		out.close();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		cache = getCacheDir().getAbsolutePath();
		TextView content = findViewById(R.id.content);
		content.setMovementMethod(new ScrollingMovementMethod());

		try {
			ZipInputStream zis = new ZipInputStream(getResources().openRawResource(R.raw.schema));
			ZipEntry ze;
			while ((ze = zis.getNextEntry()) != null) {
				saveToFile(zis, cache + "/" + ze.getName());
			}
			zis.close();

			InputStream in = getResources().openRawResource(R.raw.test);
			saveToFile(in, cache + "/test.bdoc");
			in.close();

			ByteArrayInputStream bin = new ByteArrayInputStream(new byte[] {});
			saveToFile(bin, cache + "/EE_T.xml");
			bin.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		digidoc.initializeLib("libdigidocpp Android", cache);

		Container doc = Container.open(cache + "/test.bdoc");
		content.append("DataFiles:\n");
		for(DataFile file : doc.dataFiles()) {
			content.append(file.fileName() + "\n");
		}

		content.append("\nSignatures:\n");
		for(Signature s : doc.signatures()) {
			Signature.Validator v = new Signature.Validator(s);
			content.append("ID: " + s.id() + "\n");
			content.append("Signer: " + s.signedBy() + "\n");
			content.append("Signing time: " + s.trustedSigningTime() + "\n");
			content.append("Signature status: " + v.status().toString() + "\n");
			if (!v.diagnostics().isEmpty()) {
				content.append("Diagnostics:\n" + v.diagnostics() + "\n");
			}
		}

		// For testing
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_READWRITE_STORAGE);
		} else {
			runTest(Environment.getExternalStorageDirectory());
		}

		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, new X509TrustManager[]{
					new X509TrustManager() {
						@Override
						public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
						}

						@Override
						public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
						}

						@Override
						public X509Certificate[] getAcceptedIssuers() {
							return new X509Certificate[0];
						}
					}
			}, null);
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
			HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
				@Override
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			});
		} catch (Exception e) {
			e.printStackTrace();
		}

		findViewById(R.id.run).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View button) {
				button.setEnabled(false);
				new DownloadTask(MainActivity.this).execute();
			}
		});
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQUEST_READWRITE_STORAGE && grantResults.length > 0 &&
				grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			runTest(Environment.getExternalStorageDirectory());
		}
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
	}

	private void runTest(File path) {
		final TextView content = findViewById(R.id.content);
		File[] list = path.listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				String lowerCase = name.toLowerCase();
				return lowerCase.endsWith(".asice") || lowerCase.endsWith(".sce") ||
						lowerCase.endsWith(".asics") || lowerCase.endsWith(".scs") ||
						lowerCase.endsWith(".bdoc") || lowerCase.endsWith(".ddoc") ||
						lowerCase.endsWith(".adoc") || lowerCase.endsWith(".edoc") ||
						lowerCase.endsWith(".pdf");
			}
		});
		if (list == null || list.length == 0) {
			return;
		}

		JSONArray result = new JSONArray();
		JSONObject r = new JSONObject();
		try {
			r.put("version", digidocJNI.version());
			r.put("start", new Timestamp(System.currentTimeMillis()).toString());
			r.put("result", result);
		} catch (Exception e) {
			e.printStackTrace();
		}

		int poscount = 0;
		for (File file : list) {
			String status = "OK";
			String diagnostics = "";
			JSONArray dataFiles = new JSONArray();
			try {
				Log.i("VALIDATE", "Opening file " + file.getAbsolutePath());
				Container doc = Container.open(file.getAbsolutePath());
				for(DataFile dataFile : doc.dataFiles()) {
					JSONObject f = new JSONObject();
					f.put("f", dataFile.fileName());
					f.put("m", dataFile.mediaType());
					f.put("s", dataFile.fileSize());
					dataFiles.put(f);
				}
				for(Signature signature : doc.signatures()) {
					Signature.Validator v = new Signature.Validator(signature);
					if (v.status().equals(Signature.Validator.Status.Invalid) ||
						v.status().equals(Signature.Validator.Status.Unknown)) {
						status = "NOT";
						diagnostics = v.diagnostics();
					}
				}
			} catch (Exception e) {
				status = "NOT";
				diagnostics = e.getMessage();
			}
			if (status.equals("OK")) {
				++poscount;
			}
			try {
				JSONObject o = new JSONObject();
				o.put("f", file.getName());
				o.put("s", status);
				o.put("d", diagnostics);
				o.put("t", new Timestamp(System.currentTimeMillis()).toString());
				o.put("c", dataFiles);
				result.put(o);
			} catch (Exception e) {
				e.printStackTrace();
			}
			content.setText("File count: " + result.length() + ", positive count: " + poscount + "\n");
		}

		try {
			FileInputStream in = new FileInputStream(cache + "/digidocpp.log");
			byte[] logContent = new byte[(int) in.getChannel().size()];
			in.read(logContent);
			in.close();
			new UploadTask(this, "text/plain", logContent).execute();
			new UploadTask(this, "application/json", r.toString().getBytes()).execute();

			in = new FileInputStream(cache + "/digidocpp.log");
			saveToFile(in, Environment.getExternalStorageDirectory().getAbsolutePath() + "/digidocpp.log");
			in.close();
			FileOutputStream out = new FileOutputStream(Environment.getExternalStorageDirectory().getAbsolutePath() + "/result.json");
			out.write(r.toString().getBytes(StandardCharsets.UTF_8));
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		findViewById(R.id.run).setEnabled(true);
	}

	private static void deleteRecursive(File fileOrDirectory) {
		if (fileOrDirectory.isDirectory())
			for (File child : fileOrDirectory.listFiles())
				deleteRecursive(child);
		fileOrDirectory.delete();
	}

	static private abstract class URLTask extends AsyncTask<Void, Void, Exception> {
		URL url;
		final WeakReference<MainActivity> mActivityRef;

		URLTask(MainActivity activity) {
			mActivityRef = new WeakReference<>(activity);
			try {
				EditText text = activity.findViewById(R.id.search);
				url = new URL(text.getText().toString());
			} catch (MalformedURLException e) {
				e.printStackTrace();
			}
		}

		@Override
		protected void onPostExecute(Exception e) {
			if (e != null) {
				e.printStackTrace();
			}
		}
	}

	static private class DownloadTask extends URLTask {
		private final File path;
		DownloadTask(MainActivity activity) {
			super(activity);
			path = new File(activity.getCacheDir() + "/validate");
			deleteRecursive(path);
			path.mkdir();
		}

		@Override
		protected Exception doInBackground(Void... tmp) {
			try {
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				if (connection.getResponseCode() >= 400) {
					throw new IOException("Failed to connect service" + connection.getResponseMessage());
				}
				InputStream is = connection.getInputStream();
				ZipInputStream zis = new ZipInputStream(is);
				ZipEntry ze;
				while ((ze = zis.getNextEntry()) != null) {
					try {
						saveToFile(zis, path + "/" + ze.getName());
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				is.close();
				zis.close();
				return null;
			} catch(Exception e) {
				return e;
			}
		}

		@Override
		protected void onPostExecute(Exception e) {
			super.onPostExecute(e);
			if (e == null && mActivityRef.get() != null) {
				mActivityRef.get().runTest(path);
			}
		}
	}

	static private class UploadTask extends URLTask {
		private final String contentType;
		private final byte[] data;

		UploadTask(MainActivity activity, String contentType, byte[] data) {
			super(activity);
			this.contentType = contentType;
			this.data = data;
		}

		@Override
		protected Exception doInBackground(Void... tmp) {
			try {
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				connection.setDoOutput(true);
				connection.setRequestMethod("PUT");
				connection.setRequestProperty("Content-Type", contentType);
				connection.setFixedLengthStreamingMode(data.length);
				OutputStream os = connection.getOutputStream();
				os.write(data);
				os.close();
				if (connection.getResponseCode() != 200) {
					throw new IOException("Failed to upload data" + connection.getResponseMessage());
				}
				return null;
			} catch(Exception e) {
				return e;
			}
		}

		@Override
		protected void onPostExecute(Exception e) {
			super.onPostExecute(e);
			if (contentType.equals("application/json") && mActivityRef.get() != null) {
				TextView content = mActivityRef.get().findViewById(R.id.content);
				content.append("DONE");
			}
		}
	}
}