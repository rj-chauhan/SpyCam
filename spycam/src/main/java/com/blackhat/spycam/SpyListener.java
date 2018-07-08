package com.blackhat.spycam;

import java.util.TreeMap;


public interface SpyListener {

    void onCaptureDone(String pictureUrl, byte[] pictureData);

    void onDoneCapturingAllPhotos(TreeMap<String, byte[]> picturesTaken);

}
