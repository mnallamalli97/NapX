package com.sai.drowsydriver;

import android.content.Context;
import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Created by meharnallamalli on 4/24/18.
 */
public class ImageDetector {

    public class DetectionResult {
        public String eye;
        public String yawn;
        public boolean sleep;

        public DetectionResult(String eye, String yawn, boolean sleep) {
            this.eye = eye;
            this.yawn = yawn;
            this.sleep = sleep;
        }
    }

    public static final int SCALE_FACTOR = 2;
    public static final int CLOSED_EYE_FRAME_LIMIT = 4;
    public static final int YAWN_FRAME_LIMIT = 4;
    public static final int YAWN_COUNT_PER_MIN = 2;
    private static final int CLOSED_EYE_COUNT_PER_MIN = 2;
    private static final Scalar OPEN_EYE_COLOR = new Scalar(0, 255, 0, 255);
    private static final Scalar FACE_COLOR = new Scalar(0, 0, 255, 255);
    private static final Scalar MOUTH_COLOR = new Scalar(255, 0, 255, 255);
    private final String TAG = "ImageDetector";

    private CascadeClassifier mOpenEyeClassifier;
    private CascadeClassifier mFaceClassifier;
    private int mClosedEyeFrameCount = 0;
    private int mYawnFrameCount = 0;
    private List<Long> mYawnDetectedTimes;
    private List<Long> mClosedEyeDetectedTimes;

    public ImageDetector(Context context) {
        mOpenEyeClassifier = loadClassifier(context, R.raw.haarcascade_eye_tree_eyeglasses, "haarcascade_eye_tree_eyeglasses.xml");
        mFaceClassifier = loadClassifier(context, R.raw.haarcascade_frontalface_alt_tree, "haarcascade_frontalface_alt_tree.xml");

        mYawnDetectedTimes = new ArrayList<>();
        mClosedEyeDetectedTimes = new ArrayList<>();
    }

    private CascadeClassifier loadClassifier(Context context, int resId, String fileName) {
        CascadeClassifier classifier = null;
        try {
            // load cascade file from application resources
            InputStream is = context.getResources().openRawResource(resId);
            File cascadeDir = context.getDir("cascade", Context.MODE_PRIVATE);
            File cascadeFile = new File(cascadeDir, fileName);
            FileOutputStream os = new FileOutputStream(cascadeFile);

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            is.close();
            os.close();

            classifier = new CascadeClassifier(cascadeFile.getAbsolutePath());
            if (classifier.empty()) {
                Log.e(TAG, "Failed to load cascade classifier");
                classifier = null;
                throw new InvalidObjectException("Not able to load cascade classifier");
            }

        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed to load cascade. Exception thrown: " + e);
        }

        return classifier;
    }

    public DetectionResult Detect(Mat gray, Mat rgba) {
        Size sz = new Size(gray.size().width / SCALE_FACTOR, gray.size().height / SCALE_FACTOR);
        Mat small = new Mat(sz, gray.type());
        Imgproc.resize(gray, small, sz);
        gray = small;

        Imgproc.equalizeHist(gray, gray);
        Long currentTime = System.currentTimeMillis();

        Rect[] faces = _DetectFace(gray, rgba);
        String eyeText = "no";
        String yawnText = "no";

        if (faces.length > 0) {
            boolean foundEyes = false;
            boolean foundYawn = false;
            for (int i = 0; i < faces.length; i++) {
                Mat grayFace = new Mat(gray, faces[i]);
                if(_DetectEyes(grayFace, rgba, faces[i]) > 0) {
                    foundEyes = true;
                }

                if(_DetectYawn(grayFace, rgba)) {
                    foundYawn = true;
                }
            }

            if (!foundEyes && mClosedEyeFrameCount >= 0) {
                Log.w(TAG, "Eye Closed");
                mClosedEyeFrameCount++;
                if (mClosedEyeFrameCount >= CLOSED_EYE_FRAME_LIMIT) {
                    mClosedEyeFrameCount = -1;
                    mClosedEyeDetectedTimes.add(currentTime);
                    eyeText = "yes";
                }
            } else {
                Log.w(TAG, "Eye Opened");
                mClosedEyeFrameCount = 0;
            }

            if (foundYawn && mYawnFrameCount >= 0) {
                mYawnFrameCount++;
                if (mYawnFrameCount >= YAWN_FRAME_LIMIT) {
                    Log.w(TAG, "Yawn Detected");
                    mYawnDetectedTimes.add(currentTime);
                    mYawnFrameCount = -1;
                    yawnText = "yes";
                }
            } else {
                mYawnFrameCount = 0;
            }
        } else {
            mClosedEyeFrameCount = 0;
            mYawnFrameCount = 0;
        }


        // keep only detected yawns in last 1 minute
        RemoveExpiredDetections(mYawnDetectedTimes, currentTime);
        RemoveExpiredDetections(mClosedEyeDetectedTimes, currentTime);

        boolean sleep = false;
        if(mYawnDetectedTimes.size() >= YAWN_COUNT_PER_MIN && mClosedEyeDetectedTimes.size() >= CLOSED_EYE_COUNT_PER_MIN) {
            mYawnDetectedTimes.clear();
            mClosedEyeDetectedTimes.clear();
            sleep = true;
        }

        return new DetectionResult(eyeText, yawnText, sleep);
    }

    private void RemoveExpiredDetections(List<Long> list, Long currentTime) {
        while(!list.isEmpty() && ((currentTime - list.get(0)) > 60 * 6000)) {
            list.remove(0);
        }
    }

    private int _DetectEyes(Mat gray, Mat rgba, Rect face) {
        int absoluteSize = getAbsoluteSize(0.2f, gray);

        MatOfRect eyes = new MatOfRect();
        mOpenEyeClassifier.detectMultiScale(gray, eyes, 1.1, 2, 2,
                new Size(absoluteSize, absoluteSize), new Size());

        Rect[] array = eyes.toArray();
        for (int i = 0; i < array.length; i++) {
            Point tl = new Point((face.x + array[i].tl().x) * SCALE_FACTOR, (face.y + array[i].tl().y) * SCALE_FACTOR);
            Point br = new Point((face.x + array[i].br().x) * SCALE_FACTOR, (face.y + array[i].br().y) * SCALE_FACTOR);
            Core.rectangle(rgba, tl, br, OPEN_EYE_COLOR, 3);
        }

        return array.length;
    }

    private Rect[] _DetectFace(Mat gray, Mat rgba) {
        int absoluteSize = getAbsoluteSize(0.36f, gray);

        MatOfRect faces = new MatOfRect();
        mFaceClassifier.detectMultiScale(gray, faces, 1.1, 2, 2,
                new Size(absoluteSize, absoluteSize), new Size());

        Rect[] array = faces.toArray();
        for (int i = 0; i < array.length; i++) {
            Point tl = new Point(array[i].tl().x * SCALE_FACTOR, array[i].tl().y * SCALE_FACTOR);
            Point br = new Point(array[i].br().x * SCALE_FACTOR, array[i].br().y * SCALE_FACTOR);
            Core.rectangle(rgba, tl, br, FACE_COLOR, 3);
        }

        return array;
    }

    private int getAbsoluteSize(float relativeSize, Mat gray) {
        int absoluteSize = 0;
        int height = gray.rows();
        if (Math.round(height * relativeSize) > 0) {
            absoluteSize = Math.round(height * relativeSize);
        }
        return absoluteSize;
    }

    private boolean _DetectYawn(Mat gray, Mat rgba) {
        Rect mouthRect = new Rect(gray.width()/4, gray.height()/2, gray.width()/2, gray.height()/2);
        Mat roi = new Mat(gray, mouthRect);
        Imgproc.threshold(roi, roi, 62, 255,Imgproc.THRESH_BINARY_INV);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(roi, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE);

        // Find max contour area
        Iterator<MatOfPoint> each = contours.iterator();
        double maxArea = 0.0;
        while (each.hasNext()) {
            MatOfPoint wrapper = each.next();
            double area = Imgproc.contourArea(wrapper);
            if (area > maxArea) {
                maxArea = area;
            }

        }

        Imgproc.drawContours(rgba, contours, -1, MOUTH_COLOR);
        //Log.d(TAG, "max contour area: " + maxArea + " number of contours: " + contours.size() + " mouth area: " + mouthRect.area());

        if(maxArea/mouthRect.area() > 0.05) {
            return true;
        }

        return false;
    }
}
