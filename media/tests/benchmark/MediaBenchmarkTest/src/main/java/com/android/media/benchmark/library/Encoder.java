/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.media.benchmark.library;

import android.media.MediaCodec;
import android.media.MediaCodec.CodecException;
import android.media.MediaFormat;
import android.view.Surface;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class Encoder implements IBufferXfer.IReceiveBuffer {
    // Change in AUDIO_ENCODE_DEFAULT_MAX_INPUT_SIZE should also be taken to
    // kDefaultAudioEncodeFrameSize present in BenchmarkCommon.h
    private static final int AUDIO_ENCODE_DEFAULT_MAX_INPUT_SIZE = 4096;
    private static final String TAG = "Encoder";
    private static final boolean DEBUG = false;
    private static final int kQueueDequeueTimeoutUs = 1000;
    private final Object mLock = new Object();
    private MediaCodec mCodec = null;
    private String mMime;
    private Stats mStats;

    private int mOffset;
    private int mFrameSize;
    private int mNumInputFrame;
    private int mNumFrames = 0;
    private int mFrameRate;
    private int mSampleRate;
    private long mInputBufferSize;

    private int mMinOutputBuffers = 0;
    private int mNumOutputBuffers = 0;
    private boolean mUseSurface = false;

    private boolean mSawInputEOS;
    private boolean mSawOutputEOS;
    private boolean mSignalledError;

    private FileInputStream mInputStream = null;
    private FileOutputStream mOutputStream = null;
    private IBufferXfer.ISendBuffer mIBufferSend = null;
    /* success for encoder */
    public static final int ENCODE_SUCCESS = 0;
    /* some error happened during encoding */
    public static final int ENCODE_ENCODER_ERROR = -1;
    /* error while creating an encoder */
    public static final int ENCODE_CREATE_ERROR = -2;
    public Encoder() {
        mStats = new Stats();
        mNumInputFrame = 0;
        mSawInputEOS = false;
        mSawOutputEOS = false;
        mSignalledError = false;
    }
    @Override
    public boolean receiveBuffer(IBufferXfer.BufferXferInfo info) {
        if (DEBUG) {
            Log.d(TAG,"Encoder Getting buffers from external: "
                + " Bytes Read: " + info.bytesRead
                + " PresentationUs " + info.presentationTimeUs
                + " flags: " + info.flag);
        }
        MediaCodec codec = (MediaCodec)info.obj;
        codec.queueInputBuffer(info.idx, 0, info.bytesRead,
            info.presentationTimeUs, info.flag);
        return true;
    }
    @Override
    public boolean connect(IBufferXfer.ISendBuffer receiver) {
        mIBufferSend = receiver;
        return true;
    }
    public Stats getStats() { return mStats; };

    /**
     * Setup of encoder
     *
     * @param encoderOutputStream Will dump the encoder output in this stream if not null.
     * @param fileInputStream     Will read the decoded output from this stream
     */
    public void setupEncoder(FileOutputStream encoderOutputStream,
                             FileInputStream fileInputStream) {
        this.mInputStream = fileInputStream;
        this.mOutputStream = encoderOutputStream;
    }
    /**
     * Setup of encoder
     *
     * @param useSurface, indicates that application is using surface for input
     * @param numOutputBuffers indicate the minimum buffers to signal Output
     * end of stream
     */
    public void setupEncoder(boolean useSurface, int numOutputBuffers) {
        this.mUseSurface = useSurface;
        this.mMinOutputBuffers = numOutputBuffers;
    }

    private MediaCodec createCodec(String codecName, String mime) throws IOException {
        try {
            MediaCodec codec;
            if (codecName.isEmpty()) {
                Log.i(TAG, "Mime type: " + mime);
                if (mime != null) {
                    codec = MediaCodec.createEncoderByType(mime);
                    Log.i(TAG, "Encoder created for mime type " + mime);
                    return codec;
                } else {
                    Log.e(TAG, "Mime type is null, please specify a mime type to create encoder");
                    return null;
                }
            } else {
                codec = MediaCodec.createByCodecName(codecName);
                Log.i(TAG, "Encoder created with codec name: " + codecName + " and mime: " + mime);
                return codec;
            }
        } catch (IllegalArgumentException ex) {
            ex.printStackTrace();
            Log.e(TAG, "Failed to create encoder for " + codecName + " mime: " + mime);
            return null;
        }
    }
    /**
     * Creates and configures the encoder with the given name, format and mime.
     * provided a valid list of parameters are passed as inputs. This is needed
     * to first configure the codec and then may be get surface etc and then
     * use for encode.
     *
     * @param codecName    Will create the encoder with codecName
     * @param encodeFormat Format of the output data
     * @param mime         For creating encode format
     * @return ENCODE_SUCCESS if encode was successful,
     *         ENCODE_CREATE_ERROR for encoder not created
     * @throws IOException If the codec cannot be created.
     */

    public int createAndConfigure(String codecName, MediaFormat encodeFormat,
                                  String mime) throws IOException {
        if (mCodec == null) {
            mMime = mime;
            mCodec = createCodec(codecName, mime);
            if (mCodec == null) {
                return ENCODE_CREATE_ERROR;
            }
            /*Configure Codec*/
            try {
                mCodec.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch(IllegalArgumentException
                  | IllegalStateException
                  | MediaCodec.CryptoException e) {
                Log.e(TAG, "Failed to configure " + mCodec.getName() + " encoder.");
                e.printStackTrace();
                return ENCODE_CREATE_ERROR;
            }
        }
        return ENCODE_SUCCESS;
    }
    /**
     * Requests the surface to use as input to the encoder
     * @return a valid surface or null if not called after configure.
     */
    public Surface getInputSurface() {
        Surface inputSurface = null;
        if (mCodec != null) {
            inputSurface = mCodec.createInputSurface();
        }
        return inputSurface;
    }
    /**
     * Encodes the given raw input file and measures the performance of encode operation,
     * provided a valid list of parameters are passed as inputs.
     *
     * @param codecName    Will create the encoder with codecName
     * @param mime         For creating encode format
     * @param encodeFormat Format of the output data
     * @param frameSize    Size of the frame
     * @param asyncMode    Will run on async implementation if true
     * @return ENCODE_SUCCESS if encode was successful ,ENCODE_ENCODER_ERROR for fail,
     *         ENCODE_CREATE_ERROR for encoder not created
     * @throws IOException If the codec cannot be created.
     */
    public int encode(String codecName, MediaFormat encodeFormat, String mime, int frameRate,
            int sampleRate, int frameSize, boolean asyncMode)
            throws IOException, InterruptedException {
        mInputBufferSize = (mInputStream != null) ? mInputStream.getChannel().size() : 0;
        mOffset = 0;
        mFrameRate = frameRate;
        mSampleRate = sampleRate;
        long sTime = mStats.getCurTime();
        if (mCodec == null) {
            int status = createAndConfigure(codecName, encodeFormat, mime);
            if(status != ENCODE_SUCCESS) {
              return status;
            }
        }
        if (!mUseSurface) {
            if (mMime.startsWith("video/")) {
                mFrameSize = frameSize;
            } else {
                int maxInputSize = AUDIO_ENCODE_DEFAULT_MAX_INPUT_SIZE;
                MediaFormat format = mCodec.getInputFormat();
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                }
                mFrameSize = frameSize;
                if (mFrameSize > maxInputSize && maxInputSize > 0) {
                    mFrameSize = maxInputSize;
                }
            }
            mNumFrames = (int) ((mInputBufferSize + mFrameSize - 1) / mFrameSize);
        }
        if (asyncMode) {
            mCodec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec,
                                                   int inputBufferId) {
                    try {
                        mStats.addInputTime();
                        onInputAvailable(mediaCodec, inputBufferId);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log.e(TAG, e.toString());
                    }
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec,
                                                    int outputBufferId,
                                                    @NonNull MediaCodec.BufferInfo bufferInfo) {
                    mStats.addOutputTime();
                    onOutputAvailable(mediaCodec, outputBufferId, bufferInfo);
                    if (mSawOutputEOS) {
                        Log.i(TAG, "Saw output EOS");
                        synchronized (mLock) { mLock.notify(); }
                    }
                }

                @Override
                public void onError(@NonNull MediaCodec mediaCodec, @NonNull CodecException e) {
                    mSignalledError = true;
                    Log.e(TAG, "Codec Error: " + e.toString());
                    e.printStackTrace();
                    synchronized (mLock) { mLock.notify(); }
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec,
                                                  @NonNull MediaFormat format) {
                    Log.i(TAG, "Output format changed. Format: " + format.toString());
                }
            });
        }
        mCodec.start();
        long eTime = mStats.getCurTime();
        mStats.setInitTime(mStats.getTimeDiff(sTime, eTime));
        mStats.setStartTime();
        if (asyncMode) {
            try {
                synchronized (mLock) {
                    while (!mSawOutputEOS && !mSignalledError) {
                        mLock.wait();
                    }
                    if (mSignalledError) {
                        return ENCODE_ENCODER_ERROR;
                    }
                }
            } catch (InterruptedException e) {
                throw e;
            }
        } else {
            while (!mSawOutputEOS && !mSignalledError) {
                /* Queue input data */
                if (!mSawInputEOS && !mUseSurface) {
                    int inputBufferId = mCodec.dequeueInputBuffer(kQueueDequeueTimeoutUs);
                    if (inputBufferId < 0 && inputBufferId != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.e(TAG, "MediaCodec.dequeueInputBuffer " + "returned invalid index : " +
                                inputBufferId);
                        return ENCODE_ENCODER_ERROR;
                    }
                    mStats.addInputTime();
                    onInputAvailable(mCodec, inputBufferId);
                }
                /* Dequeue output data */
                MediaCodec.BufferInfo outputBufferInfo = new MediaCodec.BufferInfo();
                int outputBufferId =
                        mCodec.dequeueOutputBuffer(outputBufferInfo, kQueueDequeueTimeoutUs);
                if (outputBufferId < 0) {
                    if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outFormat = mCodec.getOutputFormat();
                        Log.i(TAG, "Output format changed. Format: " + outFormat.toString());
                    } else if (outputBufferId != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.e(TAG, "MediaCodec.dequeueOutputBuffer" + " returned invalid index " +
                                outputBufferId);
                        return ENCODE_ENCODER_ERROR;
                    }
                } else {
                    mStats.addOutputTime();
                    if (DEBUG) {
                        Log.d(TAG, "Dequeue O/P buffer with BufferID " + outputBufferId);
                    }
                    onOutputAvailable(mCodec, outputBufferId, outputBufferInfo);
                }
            }
        }
        return ENCODE_SUCCESS;
    }

    private void onOutputAvailable(MediaCodec mediaCodec, int outputBufferId,
                                   MediaCodec.BufferInfo outputBufferInfo) {
        if (mSawOutputEOS || outputBufferId < 0) {
            if (mSawOutputEOS) {
                Log.i(TAG, "Saw output EOS");
            }
            return;
        }
        ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferId);
        if (mOutputStream != null) {
            try {

                byte[] bytesOutput = new byte[outputBuffer.remaining()];
                outputBuffer.get(bytesOutput);
                mOutputStream.write(bytesOutput);
            } catch (IOException e) {
                e.printStackTrace();
                Log.d(TAG, "Error Dumping File: Exception " + e.toString());
                return;
            }
        }
        mNumOutputBuffers++;
        if (DEBUG) {
            Log.d(TAG,
                "In OutputBufferAvailable ,"
                + " timestamp = " + outputBufferInfo.presentationTimeUs
                + " size = " + outputBufferInfo.size
                + " flags = " + outputBufferInfo.flags);
        }

        mStats.addFrameSize(outputBuffer.remaining());
        mediaCodec.releaseOutputBuffer(outputBufferId, false);
        mSawOutputEOS = (outputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        if (mUseSurface && !mSawOutputEOS) {
            mSawOutputEOS = (mNumOutputBuffers >= mMinOutputBuffers) ? true : false;
        }
    }

    private void onInputAvailable(MediaCodec mediaCodec, int inputBufferId) throws IOException {
        if (mSawInputEOS || inputBufferId < 0 || this.mUseSurface) {
            if (mSawInputEOS) {
                Log.i(TAG, "Saw input EOS");
            }
            return;
        }
        if (mInputBufferSize < mOffset) {
            Log.e(TAG, "Out of bound access of input buffer");
            mSignalledError = true;
            return;
        }
        ByteBuffer inputBuffer = mCodec.getInputBuffer(inputBufferId);
        if (inputBuffer == null) {
            mSignalledError = true;
            return;
        }
        if (mIBufferSend != null) {
            IBufferXfer.BufferXferInfo info = new IBufferXfer.BufferXferInfo();
            info.buf = inputBuffer;
            info.idx = inputBufferId;
            info.obj = mediaCodec;
            mIBufferSend.sendBuffer(this, info);
            return;
        }
        int bufSize = inputBuffer.capacity();
        int bytesToRead = mFrameSize;
        if (mInputBufferSize - mOffset < mFrameSize) {
            bytesToRead = (int) (mInputBufferSize - mOffset);
        }
        //b/148655275 - Update Frame size, as Format value may not be valid
        if (bufSize < bytesToRead) {
            if(mNumInputFrame == 0) {
                mFrameSize = bufSize;
                bytesToRead = bufSize;
                mNumFrames = (int) ((mInputBufferSize + mFrameSize - 1) / mFrameSize);
            } else {
                mSignalledError = true;
                return;
            }
        }

        byte[] inputArray = new byte[bytesToRead];
        mInputStream.read(inputArray, 0, bytesToRead);
        inputBuffer.put(inputArray);
        int flag = 0;
        if (mNumInputFrame >= mNumFrames - 1 || bytesToRead == 0) {
            Log.i(TAG, "Sending EOS on input last frame");
            mSawInputEOS = true;
            flag = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
        }
        int presentationTimeUs;
        if (mMime.startsWith("video/")) {
            presentationTimeUs = mNumInputFrame * (1000000 / mFrameRate);
        } else {
            presentationTimeUs = mNumInputFrame * mFrameSize * 1000000 / mSampleRate;
        }
        mediaCodec.queueInputBuffer(inputBufferId, 0, bytesToRead, presentationTimeUs, flag);
        mNumInputFrame++;
        mOffset += bytesToRead;
    }

    /**
     * Stops the codec and releases codec resources.
     */
    public void deInitEncoder() {
        long sTime = mStats.getCurTime();
        if (mCodec != null) {
            mCodec.stop();
            mCodec.release();
            mCodec = null;
        }
        long eTime = mStats.getCurTime();
        mStats.setDeInitTime(mStats.getTimeDiff(sTime, eTime));
    }

    /**
     * Prints out the statistics in the information log
     *
     * @param inputReference The operation being performed, in this case decode
     * @param componentName  Name of the component/codec
     * @param mode           The operating mode: Sync/Async
     * @param durationUs     Duration of the clip in microseconds
     * @param statsFile      The output file where the stats data is written
     */
    public void dumpStatistics(String inputReference, String componentName, String mode,
                               long durationUs, String statsFile) throws IOException {
        String operation = "encode";
        mStats.dumpStatistics(
                inputReference, operation, componentName, mode, durationUs, statsFile);
    }

    /**
     * Resets the stats
     */
    public void resetEncoder() {
        mOffset = 0;
        mInputBufferSize = 0;
        mNumInputFrame = 0;
        mMinOutputBuffers = 0;
        mSawInputEOS = false;
        mSawOutputEOS = false;
        mSignalledError = false;
        mUseSurface = false;
        mStats.reset();
    }
}
