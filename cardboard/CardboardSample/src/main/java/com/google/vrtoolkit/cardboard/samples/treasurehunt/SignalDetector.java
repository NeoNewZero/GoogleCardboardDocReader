package com.google.vrtoolkit.cardboard.samples.treasurehunt;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.Image;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by moondawg on 5/4/15.
 */
public class SignalDetector {

    public class Range {
        int peakRed;
        int peakGreen;
        double sigmaRed;
        double sigmaGreen;
    }

    public Range findRange(Bitmap template, File file) {

        int H = template.getHeight();
        int W = template.getWidth();

        float increment = 3.f;
        float aspRatio = 1.8f;
        int incrementRow = Math.round(increment * aspRatio);
        int incrementCol = (int)increment;
        double minArea = 0.001 * H * W;
        int centery = Math.round(H / 2);
        int centerx = Math.round(W / 2);

        int incRow = incrementRow;
        int incCol = incrementCol;
        int b = 1;
        double[][] varMatrix = {{1, 1},
                             {1, 1}};
        Range tolerableRange = new Range();
        int reachedEdge = 0;

        while (reachedEdge == 0 && incRow < H / 2 && incCol < W / 2){
            Bitmap square = Bitmap.createBitmap(template,centerx-incCol,centery-incRow,2*incCol,2*incRow);
            int size = square.getHeight()*square.getWidth();
            double [][] histogram = getHistogram(square, 32);
            int [][] mask = findPeak(histogram);
            histogram = normalizePDF(histogram,size);
            varMatrix = getCovarianceMatrix(mask, histogram, varMatrix, size, minArea, tolerableRange);
            reachedEdge = (int) (varMatrix[0][1]);
            tolerableRange = findMaxPeak(varMatrix,histogram);

            b = b + 1;
            incRow = b * incrementRow;
            incCol = b * incrementCol;
            Log.e("findRange()", "b: " + b + " , incRow: " + incRow + " , incCol: " + incCol + " , reachedEdge: " + reachedEdge + " , sigmaRed: " + varMatrix[0][0] + " , peakRed: " + tolerableRange.peakRed);
            if(reachedEdge == 1)
            {
                FileOutputStream output = null;
                try {
                    output = new FileOutputStream(file);
                    square.compress(Bitmap.CompressFormat.JPEG, 100, output);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (null != output) {
                        try {
                            output.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        return tolerableRange;

    }


    public static int[] process(Bitmap template, Image frame, Range tolerableRange, int currx, int curry){

        Bitmap source = image2Bitmap(frame,1.f,1.f);
        frame.close();

        int H = template.getHeight();
        int W = template.getWidth();
        int halfheight = Math.round(H / 2);
        int halfwidth = Math.round(W / 2);

        //put some bounds on this
        Bitmap guess;
        if(currx - halfwidth >= 0 && currx+halfwidth < source.getWidth() && curry - halfheight >= 0 && curry+halfheight < source.getHeight())
            guess = Bitmap.createBitmap(source,currx-halfwidth,curry-halfheight, halfwidth*2, halfheight*2);
        else {
            guess = Bitmap.createBitmap(source, source.getWidth() / 2 - halfwidth, source.getHeight() / 2 - halfheight, halfwidth * 2, halfheight * 2);

        }

        int[][] maskFinger = filterImage(guess,tolerableRange);
        int[] meanLocation = computeMeanLocation(maskFinger);
        int ynew = Math.round((curry+meanLocation[0]) - H/2);
        int xnew = Math.round((currx + meanLocation[1]) - W / 2);
        int count = 0;
        while(Math.abs(meanLocation[1] - W / 2)> 5 && Math.abs(meanLocation[0] - H / 2) > 5 && count < 7) {
            Bitmap newGuess;
            if(xnew - halfwidth >= 0 && xnew+halfwidth < source.getWidth() && ynew - halfheight >= 0 && ynew+halfheight < source.getHeight())
                newGuess = Bitmap.createBitmap(source,xnew-halfwidth,ynew-halfheight, halfwidth*2, halfheight*2);
            else {
                newGuess = Bitmap.createBitmap(source, source.getWidth() / 2 - halfwidth, source.getHeight() / 2 - halfheight, halfwidth * 2, halfheight * 2);
                xnew = source.getWidth() / 2;
                ynew = source.getHeight() / 2;
            }
            maskFinger = filterImage(newGuess,tolerableRange);
            meanLocation = computeMeanLocation(maskFinger);
            ynew = Math.round((ynew+meanLocation[0]) - H/2);
            xnew = Math.round((xnew+meanLocation[1]) - W/2);
            count = count + 1;
        }

        int[] newVector = {ynew,xnew};
        return newVector;
    }

    public static Bitmap image2Bitmap(Image mImage, float yfraction, float xfraction){
        int height = mImage.getHeight();
        int width = mImage.getWidth();

        int sizey = Math.round(yfraction*height/2);
        int sizex = Math.round(xfraction*width/2);

        ByteBuffer bufferY = mImage.getPlanes()[0].getBuffer();
        byte[] bytesY = new byte[bufferY.remaining()];
        bufferY.get(bytesY);

        ByteBuffer bufferU = mImage.getPlanes()[1].getBuffer();
        byte[] bytesU = new byte[bufferU.remaining()];
        bufferU.get(bytesU);


        ByteBuffer bufferV = mImage.getPlanes()[2].getBuffer();
        byte[] bytesV = new byte[bufferV.remaining()];
        bufferV.get(bytesV);

        int[] colors = new int[2*sizey*2*sizex];
        for(int y = height/2-sizey; y < height/2+sizey; y++){
            for(int x = width/2-sizex; x < width/2+sizex; x++){
                int Y = bytesY[y * width + x];
                if(Y < 0)
                    Y = 256 - Math.abs(Y);
                int U = bytesU[(y/2) * (width/2) + (x/2)];
                if(U < 0)
                    U = 256 - Math.abs(U);
                int V = bytesV[(y/2) * (width/2) + (x/2)];
                if(V < 0)
                    V = 256 - Math.abs(V);
                int A = 255;
                int R = Math.max(0, Math.min(255, (int) (Y + (1.370705 * (V-128)))));
                int G = Math.max(0, Math.min(255, (int) (Y - (0.698001 * (V-128)) - (0.337633 * (U-128)))));
                int B = Math.max(0, Math.min(255, (int) (Y + (1.732446 * (U-128)))));
                int color = Color.argb(A, R, G, B);
                colors[(y-height/2+sizey) * 2*sizex + (x-width/2+sizex)] = color;
            }
        }

        return Bitmap.createBitmap(colors,sizex*2, sizey*2, Bitmap.Config.ARGB_8888);
    }

    private static double[][] getHistogram(Bitmap square, int quantizeLevel){
        int height = square.getHeight();
        int width = square.getWidth();

        double [][] histogram = new double[quantizeLevel][quantizeLevel];
        for(int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++){
                int red = square.getPixel(c, r) ;
                red = red & 0x00FF0000;
                red = red >> 16;
                red = red * quantizeLevel / 256;
                int green = square.getPixel(c, r) ;
                green = green & 0x0000FF00;
                green = green >> 8;
                green = green * quantizeLevel / 256;
                histogram[red][green] += 1;
            }
        }

        return histogram;
    }

    private static int[][] findPeak(double[][] pdf){
        int height = pdf.length;
        int width = pdf[0].length;
        int[][] mask = new int[height][width];
        double[][] pdfPad = new double[height+2][width+2];
        for(int i = 1; i <= height; i++){
            for(int j = 1; j <= width; j++){
                pdfPad[i][j] = pdf[i-1][j-1];
            }
        }
        for(int m = 1; m <= height; m++) {
            for (int n = 1; n <= width; n++) {
                int peakFound = 1;
                double center = pdfPad[m][n];
                for (int c1 = -1; c1 <= 1; c1++) {
                    for (int c2 = -1; c2 <= 1; c2++) {
                        if (center <= pdfPad[m + c1][n + c2] && (c1 != 0 || c2 != 0))
                            peakFound = 0;
                    }
                }
                mask[m-1][n-1] = peakFound;
            }
        }
        return mask;
    }

    private static double[][] normalizePDF(double[][] pdf, int normFactor){
        int height = pdf.length;
        int width  = pdf[0].length;

        for(int i = 0; i < height; i++){
            for(int j = 0; j < width; j++){
                pdf[i][j] = pdf[i][j]/normFactor;
            }
        }
        return pdf;

    }

    private static double[][] getCovarianceMatrix(int [][] mask, double [][] pdf, double [][] varMatrix, int size, double minArea, Range tolerableRange) {
        int reachedEdge = 0;
        double[][] covMatrix = new double[2][2];
        int height = pdf.length;
        int width = pdf[0].length;
        int numberOfPeaks = 0;
        double max = 0;
        int i = 0;
        int j = 0;
        for (int m = 0; m < height; m++) {
            for (int n = 0; n < width; n++) {
                numberOfPeaks = numberOfPeaks + mask[m][n];
                if (max < pdf[m][n]) {
                    max = pdf[m][n];
                    i = m;
                    j = n;
                }
            }
        }

        int[][] vectorOfPeaks = new int[numberOfPeaks][2];
        int a = 0;
        for (int m = 0; m < height; m++) {
            for (int n = 0; n < width; n++) {
                if (mask[m][n] == 1) {
                    vectorOfPeaks[a][0] = m;
                    vectorOfPeaks[a][1] = n;
                    a = a + 1;
                }
            }
        }

        if (numberOfPeaks > 1){
            for (int t = 0; t < vectorOfPeaks.length; t++) {
                int x = vectorOfPeaks[t][0];
                int y = vectorOfPeaks[t][1];
                if (Math.abs(x - i) > 3 * Math.sqrt(varMatrix[0][0]) && pdf[x][y] * size > minArea)
                    reachedEdge = 1;
                if (Math.abs(y - j) > 3 * Math.sqrt(varMatrix[1][1]) && pdf[x][y] * size > minArea)
                    reachedEdge = 1;
            }
        }

        double varx = 0;
        for (int m = 0; m < height; m++) {
            for (int n = 0; n < width; n++) {
                varx = varx + Math.pow((m - i),2) * pdf[m][n];
            }
        }

        double vary = 0;
        for (int m = 0; m < height; m++) {
            for (int n = 0; n < width; n++) {
                vary = vary + Math.pow((n - j),2) * pdf[m][n];
            }
        }


        covMatrix[0][0] = varx;
        covMatrix[1][1] = vary;
        covMatrix[0][1] = reachedEdge;

        return covMatrix;

    }

    private Range findMaxPeak(double[][] covMatrix, double[][] pdf){
        int height = pdf.length;
        int width = pdf[0].length;
        double max = 0;
        int i = 0;
        int j = 0;
        for (int m = 0; m < height; m++) {
            for (int n = 0; n < width; n++) {
                if (max < pdf[m][n]) {
                    max = pdf[m][n];
                    i = m;
                    j = n;
                }
            }
        }

        Range range = new Range();
        range.peakRed = i;
        range.peakGreen = j;
        range.sigmaRed = covMatrix[0][0];
        range.sigmaGreen = covMatrix[1][1];
        return range;
    }

    private static int[][] filterImage(Bitmap guess, Range tolerableRange){

        int height = guess.getHeight();
        int width = guess.getWidth();
        double sigmared = 2*Math.sqrt(tolerableRange.sigmaRed);
        double sigmagreen = 2*Math.sqrt(tolerableRange.sigmaGreen);
        int[][] mask = new int[height][width];

        for (int m = 0; m < height; m++) {
            for (int n = 0; n < width; n++) {
                int red = guess.getPixel(n, m) ;
                red = red & 0x00FF0000;
                red = red >> 16;
                red = red * 32 / 256;
                int green = guess.getPixel(n, m) ;
                green = green & 0x0000FF00;
                green = green >> 8;
                green = green * 32 / 256;

                double diffred = Math.abs(red - tolerableRange.peakRed);
                double diffgreen = Math.abs(green - tolerableRange.peakGreen);
                if (diffred <= sigmared && diffgreen <= sigmagreen)
                    mask[m][n] = 1;
            }
        }

        return mask;
    }

    private static int[] computeMeanLocation(int[][] maskFinger){
        int height = maskFinger.length;
        int width = maskFinger[0].length;
        int normFactor = 0;
        for (int m = 0; m < height; m++) {
            for (int n = 0; n < width; n++) {
                normFactor += maskFinger[m][n];
            }
        }

        int mux = 0;
        int muy = 0;
        for (int m = 0; m < height; m++) {
            for (int n = 0; n < width; n++) {
                mux = mux + n * maskFinger[m][n];
                muy = muy + m * maskFinger[m][n];
            }
        }
        try{
            mux = mux/normFactor;
        }catch(ArithmeticException e)
        {

        }
        try {
            muy = muy / normFactor;
        }catch (ArithmeticException e)
        {

        }

        int[] displacementVector = {muy,mux};
        return displacementVector;
    }
}
