//MainActivity1.java
package eye.application;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import org.opencv.android.OpenCVLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity1 extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<Intent> galleryLauncher;

    private String currentPhotoPath;
    private ImageView imageView;

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.d("OpenCV", "OpenCV initialization failed");
        } else {
            Log.d("OpenCV", "OpenCV initialized successfully");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d("MainActivity1", " onCreate 실행됨");

        setContentView(R.layout.activity_main);
        Log.d("MainActivity1", " setContentView 완료");

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        Button cameraButton = findViewById(R.id.start_detection_button);
        Button galleryButton = findViewById(R.id.open_gallery_button);
        imageView = findViewById(R.id.image_view);
        imageView.setImageResource(R.drawable.retina_logo);

        // 카메라 버튼
        cameraButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraIntent();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
            }
        });

        // 카메라 결과 처리
        cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                File imageFile = new File(currentPhotoPath);
                if (imageFile.exists()) {
                    compressImageFile(currentPhotoPath, 85);
                    Intent intent = new Intent(MainActivity1.this, MainActivity2.class);
                    intent.putExtra("image_path", currentPhotoPath);
                    startActivity(intent);
                    finish();
                } else {
                    Toast.makeText(this, "사진 촬영 실패", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 갤러리 버튼
        galleryButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            galleryLauncher.launch(intent);
        });

        // 갤러리 결과 처리
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri imageUri = result.getData().getData();
                if (imageUri != null) {
                    try {
                        InputStream imageStream = getContentResolver().openInputStream(imageUri);
                        Bitmap bitmap = BitmapFactory.decodeStream(imageStream);

                        File tempFile = new File(getCacheDir(), "gallery_input.jpg");
                        FileOutputStream out = new FileOutputStream(tempFile);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                        out.close();

                        Intent intent = new Intent(MainActivity1.this, MainActivity2.class);
                        intent.putExtra("image_path", tempFile.getAbsolutePath());
                        startActivity(intent);
                        finish();

                    } catch (IOException e) {
                        e.printStackTrace();
                        Toast.makeText(this, "이미지 불러오기 실패", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void launchCameraIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File photoFile = createImageFile();
            if (photoFile != null) {
                Uri photoUri = FileProvider.getUriForFile(this, "eye.application.fileprovider", photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                cameraLauncher.launch(takePictureIntent);
            }
        }
    }

    private File createImageFile() {
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        try {
            File image = File.createTempFile("captured_image_", ".jpg", storageDir);
            currentPhotoPath = image.getAbsolutePath();
            return image;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void compressImageFile(String inputPath, int quality) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(inputPath);

            ExifInterface exif = new ExifInterface(inputPath);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            }
            if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width > 1000) {
                float scale = 1000f / width;
                int newHeight = (int) (height * scale);
                bitmap = Bitmap.createScaledBitmap(bitmap, 1000, newHeight, true);
            }

            FileOutputStream out = new FileOutputStream(inputPath);
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            out.flush();
            out.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCameraIntent();
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        }
    }
}