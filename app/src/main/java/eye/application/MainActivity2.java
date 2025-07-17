package eye.application;

import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Debug;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Button;
import android.view.View;
import android.content.Intent;
import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.CLAHE;
import org.opencv.core.Core;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity2 extends AppCompatActivity {

    static {
        System.loadLibrary("pytorch_jni");
        System.loadLibrary("pytorch_vision_jni");
        if (!OpenCVLoader.initDebug()) {
            Log.d("OpenCV", "OpenCV initialization failed");
        }
    }

    private Module model;
    private ImageView imageView;
    private TextView resultText;
    private ProgressBar loadingSpinner;
    private Button retryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity2_main);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        imageView = findViewById(R.id.image_view);
        resultText = findViewById(R.id.result_text);
        loadingSpinner = findViewById(R.id.loading_spinner);
        retryButton = findViewById(R.id.retry_button);

        String imagePath = getIntent().getStringExtra("image_path");
        if (imagePath != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            Bitmap cropped = preprocessRetinaImage(bitmap);
            imageView.setImageBitmap(cropped);
            runBenchmark(cropped);
        }

        retryButton.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity2.this, MainActivity1.class));
            finish();
        });
    }

    private void runBenchmark(Bitmap bitmap) {
        resultText.setText("분석 중입니다...");
        retryButton.setVisibility(View.GONE);
        loadingSpinner.setVisibility(View.VISIBLE);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                long startLoad = System.nanoTime();
                model = Module.load(assetFilePath(this, "resnet18_mobile_3.1.pt"));
                long endLoad = System.nanoTime();
                long loadMs = (endLoad - startLoad) / 1_000_000;

                Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                        bitmap,
                        TensorImageUtils.TORCHVISION_NORM_MEAN_RGB,
                        TensorImageUtils.TORCHVISION_NORM_STD_RGB
                );

                long startInfer = System.nanoTime();
                Tensor outputTensor = model.forward(IValue.from(inputTensor)).toTensor();
                long endInfer = System.nanoTime();
                long inferMs = (endInfer - startInfer) / 1_000_000;

                float[] scores = outputTensor.getDataAsFloatArray();
                float[] probs = softmax(scores);

                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                int pid = android.os.Process.myPid();
                Debug.MemoryInfo[] memoryInfo = am.getProcessMemoryInfo(new int[]{pid});
                int appMemMb = memoryInfo[0].getTotalPss() / 1024;

                Log.d("BENCHMARK", String.format(Locale.US,
                        "Model: resnet18_mobile_3.1.pt | Load: %dms | Infer: %dms | AppRAM: %dMB",
                        loadMs, inferMs, appMemMb));

                String[] classes = {"AMD", "Diabetic Retinopathy", "Glaucoma", "Normal"};
                String result = String.format(Locale.US,
                        "AMD: %.1f%%\nDR: %.1f%%\nGlaucoma: %.1f%%\nNormal: %.1f%%",
                        probs[0]*100, probs[1]*100, probs[2]*100, probs[3]*100);

                runOnUiThread(() -> {
                    resultText.setText(result);
                    retryButton.setVisibility(View.VISIBLE);
                    loadingSpinner.setVisibility(View.GONE);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> resultText.setText("오류 발생"));
            }
        });
    }

    private float[] softmax(float[] input) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : input) if (v > max) max = v;

        float sum = 0;
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = (float) Math.exp(input[i] - max);
            sum += output[i];
        }
        for (int i = 0; i < input.length; i++) output[i] /= sum;
        return output;
    }

    private Bitmap preprocessRetinaImage(Bitmap inputBitmap) {
        Mat src = new Mat();
        Utils.bitmapToMat(inputBitmap, src);
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB);

        Mat lab = new Mat();
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_RGB2Lab);
        List<Mat> labChannels = new ArrayList<>();
        Core.split(lab, labChannels);

        CLAHE clahe = Imgproc.createCLAHE(1.0, new Size(8, 8));
        clahe.apply(labChannels.get(0), labChannels.get(0));
        Core.merge(labChannels, lab);

        Mat claheRgb = new Mat();
        Imgproc.cvtColor(lab, claheRgb, Imgproc.COLOR_Lab2RGB);

        Mat gray = new Mat();
        Imgproc.cvtColor(claheRgb, gray, Imgproc.COLOR_RGB2GRAY);
        Imgproc.medianBlur(gray, gray, 5);

        int height = gray.rows();
        int width = gray.cols();
        int minR = (int) (Math.min(height, width) * 0.25);
        int maxR = (int) (Math.min(height, width) * 0.6);

        Mat circles = new Mat();
        Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1.2, 100, 60, 45, minR, maxR);

        Rect roi;
        if (circles.cols() > 0) {
            double[] c = circles.get(0, 0);
            int x = (int) Math.round(c[0]);
            int y = (int) Math.round(c[1]);
            int r = (int) Math.round(c[2] * 0.96);
            int cropSize = (int) (r * 2.2);
            int x1 = Math.max(0, x - cropSize / 2);
            int y1 = Math.max(0, y - cropSize / 2);
            int x2 = Math.min(width, x + cropSize / 2);
            int y2 = Math.min(height, y + cropSize / 2);
            roi = new Rect(x1, y1, x2 - x1, y2 - y1);
        } else {
            roi = new Rect(0, 0, width, height);
        }

        Mat cropped = new Mat(claheRgb, roi);
        int w = cropped.cols(), h = cropped.rows();
        int size = Math.max(w, h);
        int top = (size - h) / 2, bottom = (size - h + 1) / 2;
        int left = (size - w) / 2, right = (size - w + 1) / 2;

        Mat padded = new Mat();
        Core.copyMakeBorder(cropped, padded, top, bottom, left, right, Core.BORDER_CONSTANT, new Scalar(0, 0, 0));

        Mat resized = new Mat();
        Imgproc.resize(padded, resized, new Size(224, 224));
        Bitmap output = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(resized, output);
        return output;
    }

    public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) return file.getAbsolutePath();

        try (InputStream is = context.getAssets().open(assetName);
             OutputStream os = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) os.write(buffer, 0, read);
            os.flush();
        }
        return file.getAbsolutePath();
    }
}