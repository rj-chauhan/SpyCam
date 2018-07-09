package com.blackhat.spycam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.TreeMap;

public class MainActivity extends AppCompatActivity implements SpyListener {

    public SpyService spyService;
    public Button btn;
    public ImageView image;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btn = findViewById(R.id.btnCapture);
        image =findViewById(R.id.image);
        spyService = SpyServiceImp.getInstance(this,"Spy");
        btn.setOnClickListener(view -> spyService.startCapturing(MainActivity.this));
    }

    @Override
    public void onCaptureDone(String s, byte[] bytes) {
        Toast.makeText(this, "Captured", Toast.LENGTH_SHORT).show();
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        image.setImageBitmap(Bitmap.createScaledBitmap(bmp, image.getWidth(),
                image.getHeight(), false));

    }

    @Override
    public void onDoneCapturingAllPhotos(TreeMap<String, byte[]> treeMap) {

    }
}
