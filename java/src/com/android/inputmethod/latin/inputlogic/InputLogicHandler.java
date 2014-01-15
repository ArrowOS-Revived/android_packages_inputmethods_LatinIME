/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.inputmethod.latin.inputlogic;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import com.android.inputmethod.latin.InputPointers;
import com.android.inputmethod.latin.LatinIME;
import com.android.inputmethod.latin.Suggest;
import com.android.inputmethod.latin.SuggestedWords;
import com.android.inputmethod.latin.Suggest.OnGetSuggestedWordsCallback;

/**
 * A helper to manage deferred tasks for the input logic.
 */
// TODO: Make this package private
public class InputLogicHandler implements Handler.Callback {
    final Handler mNonUIThreadHandler;
    // TODO: remove this reference.
    final LatinIME mLatinIME;
    final InputLogic mInputLogic;
    private final Object mLock = new Object();
    private boolean mInBatchInput; // synchronized using {@link #mLock}.

    private static final int MSG_GET_SUGGESTED_WORDS = 1;

    // A handler that never does anything. This is used for cases where events come before anything
    // is initialized, though probably only the monkey can actually do this.
    public static final InputLogicHandler NULL_HANDLER = new InputLogicHandler() {
        @Override
        public void destroy() {}
        @Override
        public boolean handleMessage(final Message msg) { return true; }
        @Override
        public void onStartBatchInput() {}
        @Override
        public void onUpdateBatchInput(final InputPointers batchPointers,
                final int sequenceNumber) {}
        @Override
        public void onCancelBatchInput() {}
        @Override
        public void onEndBatchInput(final InputPointers batchPointers, final int sequenceNumber) {}
        @Override
        public void getSuggestedWords(final int sessionId, final int sequenceNumber,
                final OnGetSuggestedWordsCallback callback) {}
    };

    private InputLogicHandler() {
        mNonUIThreadHandler = null;
        mLatinIME = null;
        mInputLogic = null;
    }

    public InputLogicHandler(final LatinIME latinIME, final InputLogic inputLogic) {
        final HandlerThread handlerThread = new HandlerThread(
                InputLogicHandler.class.getSimpleName());
        handlerThread.start();
        mNonUIThreadHandler = new Handler(handlerThread.getLooper(), this);
        mLatinIME = latinIME;
        mInputLogic = inputLogic;
    }

    public void destroy() {
        mNonUIThreadHandler.getLooper().quit();
    }

    /**
     * Handle a message.
     * @see android.os.Handler.Callback#handleMessage(android.os.Message)
     */
    // Called on the Non-UI handler thread by the Handler code.
    @Override
    public boolean handleMessage(final Message msg) {
        switch (msg.what) {
            case MSG_GET_SUGGESTED_WORDS:
                mLatinIME.getSuggestedWords(msg.arg1 /* sessionId */,
                        msg.arg2 /* sequenceNumber */, (OnGetSuggestedWordsCallback) msg.obj);
                break;
        }
        return true;
    }

    // Called on the UI thread by InputLogic.
    public void onStartBatchInput() {
        synchronized (mLock) {
            mInBatchInput = true;
        }
    }

    /**
     * Fetch suggestions corresponding to an update of a batch input.
     * @param batchPointers the updated pointers, including the part that was passed last time.
     * @param sequenceNumber the sequence number associated with this batch input.
     * @param forEnd true if this is the end of a batch input, false if it's an update.
     */
    // This method can be called from any thread and will see to it that the correct threads
    // are used for parts that require it. This method will send a message to the Non-UI handler
    // thread to pull suggestions, and get the inlined callback to get called on the Non-UI
    // handler thread. If this is the end of a batch input, the callback will then proceed to
    // send a message to the UI handler in LatinIME so that showing suggestions can be done on
    // the UI thread.
    private void updateBatchInput(final InputPointers batchPointers,
            final int sequenceNumber, final boolean forEnd) {
        synchronized (mLock) {
            if (!mInBatchInput) {
                // Batch input has ended or canceled while the message was being delivered.
                return;
            }
            mInputLogic.mWordComposer.setBatchInputPointers(batchPointers);
            getSuggestedWords(Suggest.SESSION_GESTURE, sequenceNumber,
                    new OnGetSuggestedWordsCallback() {
                        @Override
                        public void onGetSuggestedWords(SuggestedWords suggestedWords) {
                            // We're now inside the callback. This always runs on the Non-UI thread,
                            // no matter what thread updateBatchInput was originally called on.
                            if (suggestedWords.isEmpty()) {
                                // Use old suggestions if we don't have any new ones.
                                // Previous suggestions are found in InputLogic#mSuggestedWords.
                                // Since these are the most recent ones and we just recomputed
                                // new ones to update them, then the previous ones are there.
                                suggestedWords = mInputLogic.mSuggestedWords;
                            }
                            mLatinIME.mHandler.showGesturePreviewAndSuggestionStrip(suggestedWords,
                                    forEnd /* dismissGestureFloatingPreviewText */);
                            if (forEnd) {
                                mInBatchInput = false;
                                // The following call schedules onEndBatchInputAsyncInternal
                                // to be called on the UI thread.
                                mLatinIME.mHandler.onEndBatchInput(suggestedWords);
                            }
                        }
                    });
        }
    }

    /**
     * Update a batch input.
     *
     * This fetches suggestions and updates the suggestion strip and the floating text preview.
     *
     * @param batchPointers the updated batch pointers.
     * @param sequenceNumber the sequence number associated with this batch input.
     */
    // Called on the UI thread by InputLogic.
    public void onUpdateBatchInput(final InputPointers batchPointers,
            final int sequenceNumber) {
        updateBatchInput(batchPointers, sequenceNumber, false /* forEnd */);
    }

    /**
     * Cancel a batch input.
     *
     * Note that as opposed to onEndBatchInput, we do the UI side of this immediately on the
     * same thread, rather than get this to call a method in LatinIME. This is because
     * canceling a batch input does not necessitate the long operation of pulling suggestions.
     */
    // Called on the UI thread by InputLogic.
    public void onCancelBatchInput() {
        synchronized (mLock) {
            mInBatchInput = false;
        }
    }

    /**
     * Finish a batch input.
     *
     * This fetches suggestions, updates the suggestion strip and commits the first suggestion.
     * It also dismisses the floating text preview.
     *
     * @param batchPointers the updated batch pointers.
     * @param sequenceNumber the sequence number associated with this batch input.
     */
    // Called on the UI thread by InputLogic.
    public void onEndBatchInput(final InputPointers batchPointers, final int sequenceNumber) {
        updateBatchInput(batchPointers, sequenceNumber, true /* forEnd */);
    }

    public void getSuggestedWords(final int sessionId, final int sequenceNumber,
            final OnGetSuggestedWordsCallback callback) {
        mNonUIThreadHandler.obtainMessage(
                MSG_GET_SUGGESTED_WORDS, sessionId, sequenceNumber, callback).sendToTarget();
    }
}