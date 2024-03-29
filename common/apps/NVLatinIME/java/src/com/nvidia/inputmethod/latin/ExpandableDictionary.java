/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.nvidia.inputmethod.latin;

import android.content.Context;
import android.text.TextUtils;

import com.nvidia.inputmethod.keyboard.Keyboard;
import com.nvidia.inputmethod.keyboard.ProximityInfo;
import com.nvidia.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import com.nvidia.inputmethod.latin.UserHistoryForgettingCurveUtils.ForgettingCurveParams;

import java.util.ArrayList;
import java.util.LinkedList;

/**
 * Base class for an in-memory dictionary that can grow dynamically and can
 * be searched for suggestions and valid words.
 */
public class ExpandableDictionary extends Dictionary {

    // Bigram frequency is a fixed point number with 1 meaning 1.2 and 255 meaning 1.8.
    protected static final int BIGRAM_MAX_FREQUENCY = 255;

    private Context mContext;
    private char[] mWordBuilder = new char[BinaryDictionary.MAX_WORD_LENGTH];
    private int mMaxDepth;
    private int mInputLength;

    private boolean mRequiresReload;

    private boolean mUpdatingDictionary;

    // Use this lock before touching mUpdatingDictionary & mRequiresDownload
    private Object mUpdatingLock = new Object();

    private static final class Node {
        Node() {}
        char mCode;
        int mFrequency;
        boolean mTerminal;
        Node mParent;
        NodeArray mChildren;
        ArrayList<char[]> mShortcutTargets;
        boolean mShortcutOnly;
        LinkedList<NextWord> mNGrams; // Supports ngram
    }

    private static final class NodeArray {
        Node[] mData;
        int mLength = 0;
        private static final int INCREMENT = 2;

        NodeArray() {
            mData = new Node[INCREMENT];
        }

        void add(Node n) {
            if (mLength + 1 > mData.length) {
                Node[] tempData = new Node[mLength + INCREMENT];
                if (mLength > 0) {
                    System.arraycopy(mData, 0, tempData, 0, mLength);
                }
                mData = tempData;
            }
            mData[mLength++] = n;
        }
    }

    protected interface NextWord {
        public Node getWordNode();
        public int getFrequency();
        public ForgettingCurveParams getFcParams();
        public int notifyTypedAgainAndGetFrequency();
    }

    private static final class NextStaticWord implements NextWord {
        public final Node mWord;
        private final int mFrequency;
        public NextStaticWord(Node word, int frequency) {
            mWord = word;
            mFrequency = frequency;
        }

        @Override
        public Node getWordNode() {
            return mWord;
        }

        @Override
        public int getFrequency() {
            return mFrequency;
        }

        @Override
        public ForgettingCurveParams getFcParams() {
            return null;
        }

        @Override
        public int notifyTypedAgainAndGetFrequency() {
            return mFrequency;
        }
    }

    private static final class NextHistoryWord implements NextWord {
        public final Node mWord;
        public final ForgettingCurveParams mFcp;

        public NextHistoryWord(Node word, ForgettingCurveParams fcp) {
            mWord = word;
            mFcp = fcp;
        }

        @Override
        public Node getWordNode() {
            return mWord;
        }

        @Override
        public int getFrequency() {
            return mFcp.getFrequency();
        }

        @Override
        public ForgettingCurveParams getFcParams() {
            return mFcp;
        }

        @Override
        public int notifyTypedAgainAndGetFrequency() {
            return mFcp.notifyTypedAgainAndGetFrequency();
        }
    }

    private NodeArray mRoots;

    private int[][] mCodes;

    public ExpandableDictionary(final Context context, final String dictType) {
        super(dictType);
        mContext = context;
        clearDictionary();
        mCodes = new int[BinaryDictionary.MAX_WORD_LENGTH][];
    }

    public void loadDictionary() {
        synchronized (mUpdatingLock) {
            startDictionaryLoadingTaskLocked();
        }
    }

    public void startDictionaryLoadingTaskLocked() {
        if (!mUpdatingDictionary) {
            mUpdatingDictionary = true;
            mRequiresReload = false;
            new LoadDictionaryTask().start();
        }
    }

    public void setRequiresReload(boolean reload) {
        synchronized (mUpdatingLock) {
            mRequiresReload = reload;
        }
    }

    public boolean getRequiresReload() {
        return mRequiresReload;
    }

    /** Override to load your dictionary here, on a background thread. */
    public void loadDictionaryAsync() {
        // empty base implementation
    }

    public Context getContext() {
        return mContext;
    }

    public int getMaxWordLength() {
        return BinaryDictionary.MAX_WORD_LENGTH;
    }

    public void addWord(final String word, final String shortcutTarget, final int frequency) {
        if (word.length() >= BinaryDictionary.MAX_WORD_LENGTH) {
            return;
        }
        addWordRec(mRoots, word, 0, shortcutTarget, frequency, null);
    }

    private void addWordRec(NodeArray children, final String word, final int depth,
            final String shortcutTarget, final int frequency, Node parentNode) {
        final int wordLength = word.length();
        if (wordLength <= depth) return;
        final char c = word.charAt(depth);
        // Does children have the current character?
        final int childrenLength = children.mLength;
        Node childNode = null;
        for (int i = 0; i < childrenLength; i++) {
            final Node node = children.mData[i];
            if (node.mCode == c) {
                childNode = node;
                break;
            }
        }
        final boolean isShortcutOnly = (null != shortcutTarget);
        if (childNode == null) {
            childNode = new Node();
            childNode.mCode = c;
            childNode.mParent = parentNode;
            childNode.mShortcutOnly = isShortcutOnly;
            children.add(childNode);
        }
        if (wordLength == depth + 1 && shortcutTarget != null) {
            // Terminate this word
            childNode.mTerminal = true;
            if (isShortcutOnly) {
                if (null == childNode.mShortcutTargets) {
                    childNode.mShortcutTargets = CollectionUtils.newArrayList();
                }
                childNode.mShortcutTargets.add(shortcutTarget.toCharArray());
            } else {
                childNode.mShortcutOnly = false;
            }
            childNode.mFrequency = Math.max(frequency, childNode.mFrequency);
            if (childNode.mFrequency > 255) childNode.mFrequency = 255;
            return;
        }
        if (childNode.mChildren == null) {
            childNode.mChildren = new NodeArray();
        }
        addWordRec(childNode.mChildren, word, depth + 1, shortcutTarget, frequency, childNode);
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestions(final WordComposer composer,
            final CharSequence prevWord, final ProximityInfo proximityInfo) {
        if (reloadDictionaryIfRequired()) return null;
        if (composer.size() > 1) {
            if (composer.size() >= BinaryDictionary.MAX_WORD_LENGTH) {
                return null;
            }
            final ArrayList<SuggestedWordInfo> suggestions =
                    getWordsInner(composer, prevWord, proximityInfo);
            return suggestions;
        } else {
            if (TextUtils.isEmpty(prevWord)) return null;
            final ArrayList<SuggestedWordInfo> suggestions = CollectionUtils.newArrayList();
            runBigramReverseLookUp(prevWord, suggestions);
            return suggestions;
        }
    }

    // This reloads the dictionary if required, and returns whether it's currently updating its
    // contents or not.
    // @VisibleForTesting
    boolean reloadDictionaryIfRequired() {
        synchronized (mUpdatingLock) {
            // If we need to update, start off a background task
            if (mRequiresReload) startDictionaryLoadingTaskLocked();
            return mUpdatingDictionary;
        }
    }

    protected ArrayList<SuggestedWordInfo> getWordsInner(final WordComposer codes,
            final CharSequence prevWordForBigrams, final ProximityInfo proximityInfo) {
        final ArrayList<SuggestedWordInfo> suggestions = CollectionUtils.newArrayList();
        mInputLength = codes.size();
        if (mCodes.length < mInputLength) mCodes = new int[mInputLength][];
        final InputPointers ips = codes.getInputPointers();
        final int[] xCoordinates = ips.getXCoordinates();
        final int[] yCoordinates = ips.getYCoordinates();
        // Cache the codes so that we don't have to lookup an array list
        for (int i = 0; i < mInputLength; i++) {
            // TODO: Calculate proximity info here.
            if (mCodes[i] == null || mCodes[i].length < 1) {
                mCodes[i] = new int[ProximityInfo.MAX_PROXIMITY_CHARS_SIZE];
            }
            final int x = xCoordinates != null && i < xCoordinates.length ?
                    xCoordinates[i] : Constants.NOT_A_COORDINATE;
            final int y = xCoordinates != null && i < yCoordinates.length ?
                    yCoordinates[i] : Constants.NOT_A_COORDINATE;
            proximityInfo.fillArrayWithNearestKeyCodes(x, y, codes.getCodeAt(i), mCodes[i]);
        }
        mMaxDepth = mInputLength * 3;
        getWordsRec(mRoots, codes, mWordBuilder, 0, false, 1, 0, -1, suggestions);
        for (int i = 0; i < mInputLength; i++) {
            getWordsRec(mRoots, codes, mWordBuilder, 0, false, 1, 0, i, suggestions);
        }
        return suggestions;
    }

    @Override
    public synchronized boolean isValidWord(CharSequence word) {
        synchronized (mUpdatingLock) {
            // If we need to update, start off a background task
            if (mRequiresReload) startDictionaryLoadingTaskLocked();
            if (mUpdatingDictionary) return false;
        }
        final Node node = searchNode(mRoots, word, 0, word.length());
        // If node is null, we didn't find the word, so it's not valid.
        // If node.mShortcutOnly is true, then it exists as a shortcut but not as a word,
        // so that means it's not a valid word.
        // If node.mShortcutOnly is false, then it exists as a word (it may also exist as
        // a shortcut, but this does not matter), so it's a valid word.
        return (node == null) ? false : !node.mShortcutOnly;
    }

    protected boolean removeBigram(String word1, String word2) {
        // Refer to addOrSetBigram() about word1.toLowerCase()
        final Node firstWord = searchWord(mRoots, word1.toLowerCase(), 0, null);
        final Node secondWord = searchWord(mRoots, word2, 0, null);
        LinkedList<NextWord> bigrams = firstWord.mNGrams;
        NextWord bigramNode = null;
        if (bigrams == null || bigrams.size() == 0) {
            return false;
        } else {
            for (NextWord nw : bigrams) {
                if (nw.getWordNode() == secondWord) {
                    bigramNode = nw;
                    break;
                }
            }
        }
        if (bigramNode == null) {
            return false;
        }
        return bigrams.remove(bigramNode);
    }

    /**
     * Returns the word's frequency or -1 if not found
     */
    protected int getWordFrequency(CharSequence word) {
        // Case-sensitive search
        final Node node = searchNode(mRoots, word, 0, word.length());
        return (node == null) ? -1 : node.mFrequency;
    }

    protected NextWord getBigramWord(String word1, String word2) {
        // Refer to addOrSetBigram() about word1.toLowerCase()
        final Node firstWord = searchWord(mRoots, word1.toLowerCase(), 0, null);
        final Node secondWord = searchWord(mRoots, word2, 0, null);
        LinkedList<NextWord> bigrams = firstWord.mNGrams;
        if (bigrams == null || bigrams.size() == 0) {
            return null;
        } else {
            for (NextWord nw : bigrams) {
                if (nw.getWordNode() == secondWord) {
                    return nw;
                }
            }
        }
        return null;
    }

    private static int computeSkippedWordFinalFreq(int freq, int snr, int inputLength) {
        // The computation itself makes sense for >= 2, but the == 2 case returns 0
        // anyway so we may as well test against 3 instead and return the constant
        if (inputLength >= 3) {
            return (freq * snr * (inputLength - 2)) / (inputLength - 1);
        } else {
            return 0;
        }
    }

    /**
     * Helper method to add a word and its shortcuts.
     *
     * @param node the terminal node
     * @param word the word to insert, as an array of code points
     * @param depth the depth of the node in the tree
     * @param finalFreq the frequency for this word
     * @param suggestions the suggestion collection to add the suggestions to
     * @return whether there is still space for more words.
     */
    private boolean addWordAndShortcutsFromNode(final Node node, final char[] word, final int depth,
            final int finalFreq, final ArrayList<SuggestedWordInfo> suggestions) {
        if (finalFreq > 0 && !node.mShortcutOnly) {
            // Use KIND_CORRECTION always. This dictionary does not really have a notion of
            // COMPLETION against CORRECTION; we could artificially add one by looking at
            // the respective size of the typed word and the suggestion if it matters sometime
            // in the future.
            suggestions.add(new SuggestedWordInfo(new String(word, 0, depth + 1), finalFreq,
                    SuggestedWordInfo.KIND_CORRECTION, mDictType));
            if (suggestions.size() >= Suggest.MAX_SUGGESTIONS) return false;
        }
        if (null != node.mShortcutTargets) {
            final int length = node.mShortcutTargets.size();
            for (int shortcutIndex = 0; shortcutIndex < length; ++shortcutIndex) {
                final char[] shortcut = node.mShortcutTargets.get(shortcutIndex);
                suggestions.add(new SuggestedWordInfo(new String(shortcut, 0, shortcut.length),
                        finalFreq, SuggestedWordInfo.KIND_SHORTCUT, mDictType));
                if (suggestions.size() > Suggest.MAX_SUGGESTIONS) return false;
            }
        }
        return true;
    }

    /**
     * Recursively traverse the tree for words that match the input. Input consists of
     * a list of arrays. Each item in the list is one input character position. An input
     * character is actually an array of multiple possible candidates. This function is not
     * optimized for speed, assuming that the user dictionary will only be a few hundred words in
     * size.
     * @param roots node whose children have to be search for matches
     * @param codes the input character codes
     * @param word the word being composed as a possible match
     * @param depth the depth of traversal - the length of the word being composed thus far
     * @param completion whether the traversal is now in completion mode - meaning that we've
     * exhausted the input and we're looking for all possible suffixes.
     * @param snr current weight of the word being formed
     * @param inputIndex position in the input characters. This can be off from the depth in
     * case we skip over some punctuations such as apostrophe in the traversal. That is, if you type
     * "wouldve", it could be matching "would've", so the depth will be one more than the
     * inputIndex
     * @param suggestions the list in which to add suggestions
     */
    // TODO: Share this routine with the native code for BinaryDictionary
    protected void getWordsRec(NodeArray roots, final WordComposer codes, final char[] word,
            final int depth, final boolean completion, int snr, int inputIndex, int skipPos,
            final ArrayList<SuggestedWordInfo> suggestions) {
        final int count = roots.mLength;
        final int codeSize = mInputLength;
        // Optimization: Prune out words that are too long compared to how much was typed.
        if (depth > mMaxDepth) {
            return;
        }
        final int[] currentChars;
        if (codeSize <= inputIndex) {
            currentChars = null;
        } else {
            currentChars = mCodes[inputIndex];
        }

        for (int i = 0; i < count; i++) {
            final Node node = roots.mData[i];
            final char c = node.mCode;
            final char lowerC = toLowerCase(c);
            final boolean terminal = node.mTerminal;
            final NodeArray children = node.mChildren;
            final int freq = node.mFrequency;
            if (completion || currentChars == null) {
                word[depth] = c;
                if (terminal) {
                    final int finalFreq;
                    if (skipPos < 0) {
                        finalFreq = freq * snr;
                    } else {
                        finalFreq = computeSkippedWordFinalFreq(freq, snr, mInputLength);
                    }
                    if (!addWordAndShortcutsFromNode(node, word, depth, finalFreq, suggestions)) {
                        // No space left in the queue, bail out
                        return;
                    }
                }
                if (children != null) {
                    getWordsRec(children, codes, word, depth + 1, true, snr, inputIndex,
                            skipPos, suggestions);
                }
            } else if ((c == Keyboard.CODE_SINGLE_QUOTE
                    && currentChars[0] != Keyboard.CODE_SINGLE_QUOTE) || depth == skipPos) {
                // Skip the ' and continue deeper
                word[depth] = c;
                if (children != null) {
                    getWordsRec(children, codes, word, depth + 1, completion, snr, inputIndex,
                            skipPos, suggestions);
                }
            } else {
                // Don't use alternatives if we're looking for missing characters
                final int alternativesSize = skipPos >= 0 ? 1 : currentChars.length;
                for (int j = 0; j < alternativesSize; j++) {
                    final int addedAttenuation = (j > 0 ? 1 : 2);
                    final int currentChar = currentChars[j];
                    if (currentChar == Constants.NOT_A_CODE) {
                        break;
                    }
                    if (currentChar == lowerC || currentChar == c) {
                        word[depth] = c;

                        if (codeSize == inputIndex + 1) {
                            if (terminal) {
                                final int finalFreq;
                                if (skipPos < 0) {
                                    finalFreq = freq * snr * addedAttenuation
                                            * FULL_WORD_SCORE_MULTIPLIER;
                                } else {
                                    finalFreq = computeSkippedWordFinalFreq(freq,
                                            snr * addedAttenuation, mInputLength);
                                }
                                if (!addWordAndShortcutsFromNode(node, word, depth, finalFreq,
                                        suggestions)) {
                                    // No space left in the queue, bail out
                                    return;
                                }
                            }
                            if (children != null) {
                                getWordsRec(children, codes, word, depth + 1,
                                        true, snr * addedAttenuation, inputIndex + 1,
                                        skipPos, suggestions);
                            }
                        } else if (children != null) {
                            getWordsRec(children, codes, word, depth + 1,
                                    false, snr * addedAttenuation, inputIndex + 1,
                                    skipPos, suggestions);
                        }
                    }
                }
            }
        }
    }

    public int setBigramAndGetFrequency(String word1, String word2, int frequency) {
        return setBigramAndGetFrequency(word1, word2, frequency, null /* unused */);
    }

    public int setBigramAndGetFrequency(String word1, String word2, ForgettingCurveParams fcp) {
        return setBigramAndGetFrequency(word1, word2, 0 /* unused */, fcp);
    }

    /**
     * Adds bigrams to the in-memory trie structure that is being used to retrieve any word
     * @param word1 the first word of this bigram
     * @param word2 the second word of this bigram
     * @param frequency frequency for this bigram
     * @param fcp an instance of ForgettingCurveParams to use for decay policy
     * @return returns the final bigram frequency
     */
    private int setBigramAndGetFrequency(
            String word1, String word2, int frequency, ForgettingCurveParams fcp) {
        // We don't want results to be different according to case of the looked up left hand side
        // word. We do want however to return the correct case for the right hand side.
        // So we want to squash the case of the left hand side, and preserve that of the right
        // hand side word.
        Node firstWord = searchWord(mRoots, word1.toLowerCase(), 0, null);
        Node secondWord = searchWord(mRoots, word2, 0, null);
        LinkedList<NextWord> bigrams = firstWord.mNGrams;
        if (bigrams == null || bigrams.size() == 0) {
            firstWord.mNGrams = CollectionUtils.newLinkedList();
            bigrams = firstWord.mNGrams;
        } else {
            for (NextWord nw : bigrams) {
                if (nw.getWordNode() == secondWord) {
                    return nw.notifyTypedAgainAndGetFrequency();
                }
            }
        }
        if (fcp != null) {
            // history
            firstWord.mNGrams.add(new NextHistoryWord(secondWord, fcp));
        } else {
            firstWord.mNGrams.add(new NextStaticWord(secondWord, frequency));
        }
        return frequency;
    }

    /**
     * Searches for the word and add the word if it does not exist.
     * @return Returns the terminal node of the word we are searching for.
     */
    private Node searchWord(NodeArray children, String word, int depth, Node parentNode) {
        final int wordLength = word.length();
        final char c = word.charAt(depth);
        // Does children have the current character?
        final int childrenLength = children.mLength;
        Node childNode = null;
        for (int i = 0; i < childrenLength; i++) {
            final Node node = children.mData[i];
            if (node.mCode == c) {
                childNode = node;
                break;
            }
        }
        if (childNode == null) {
            childNode = new Node();
            childNode.mCode = c;
            childNode.mParent = parentNode;
            children.add(childNode);
        }
        if (wordLength == depth + 1) {
            // Terminate this word
            childNode.mTerminal = true;
            return childNode;
        }
        if (childNode.mChildren == null) {
            childNode.mChildren = new NodeArray();
        }
        return searchWord(childNode.mChildren, word, depth + 1, childNode);
    }

    private void runBigramReverseLookUp(final CharSequence previousWord,
            final ArrayList<SuggestedWordInfo> suggestions) {
        // Search for the lowercase version of the word only, because that's where bigrams
        // store their sons.
        Node prevWord = searchNode(mRoots, previousWord.toString().toLowerCase(), 0,
                previousWord.length());
        if (prevWord != null && prevWord.mNGrams != null) {
            reverseLookUp(prevWord.mNGrams, suggestions);
        }
    }

    /**
     * Used for testing purposes and in the spell checker
     * This function will wait for loading from database to be done
     */
    void waitForDictionaryLoading() {
        while (mUpdatingDictionary) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                //
            }
        }
    }

    protected final void blockingReloadDictionaryIfRequired() {
        reloadDictionaryIfRequired();
        waitForDictionaryLoading();
    }

    // Local to reverseLookUp, but do not allocate each time.
    private final char[] mLookedUpString = new char[BinaryDictionary.MAX_WORD_LENGTH];

    /**
     * reverseLookUp retrieves the full word given a list of terminal nodes and adds those words
     * to the suggestions list passed as an argument.
     * @param terminalNodes list of terminal nodes we want to add
     * @param suggestions the suggestion collection to add the word to
     */
    private void reverseLookUp(LinkedList<NextWord> terminalNodes,
            final ArrayList<SuggestedWordInfo> suggestions) {
        Node node;
        int freq;
        for (NextWord nextWord : terminalNodes) {
            node = nextWord.getWordNode();
            freq = nextWord.getFrequency();
            int index = BinaryDictionary.MAX_WORD_LENGTH;
            do {
                --index;
                mLookedUpString[index] = node.mCode;
                node = node.mParent;
            } while (node != null && index > 0);

            // If node is null, we have a word longer than MAX_WORD_LENGTH in the dictionary.
            // It's a little unclear how this can happen, but just in case it does it's safer
            // to ignore the word in this case.
            if (freq >= 0 && node == null) {
                suggestions.add(new SuggestedWordInfo(new String(mLookedUpString, index,
                        BinaryDictionary.MAX_WORD_LENGTH - index),
                        freq, SuggestedWordInfo.KIND_CORRECTION, mDictType));
            }
        }
    }

    /**
     * Recursively search for the terminal node of the word.
     *
     * One iteration takes the full word to search for and the current index of the recursion.
     *
     * @param children the node of the trie to search under.
     * @param word the word to search for. Only read [offset..length] so there may be trailing chars
     * @param offset the index in {@code word} this recursion should operate on.
     * @param length the length of the input word.
     * @return Returns the terminal node of the word if the word exists
     */
    private Node searchNode(final NodeArray children, final CharSequence word, final int offset,
            final int length) {
        final int count = children.mLength;
        final char currentChar = word.charAt(offset);
        for (int j = 0; j < count; j++) {
            final Node node = children.mData[j];
            if (node.mCode == currentChar) {
                if (offset == length - 1) {
                    if (node.mTerminal) {
                        return node;
                    }
                } else {
                    if (node.mChildren != null) {
                        Node returnNode = searchNode(node.mChildren, word, offset + 1, length);
                        if (returnNode != null) return returnNode;
                    }
                }
            }
        }
        return null;
    }

    protected void clearDictionary() {
        mRoots = new NodeArray();
    }

    private final class LoadDictionaryTask extends Thread {
        LoadDictionaryTask() {}
        @Override
        public void run() {
            loadDictionaryAsync();
            synchronized (mUpdatingLock) {
                mUpdatingDictionary = false;
            }
        }
    }

    private static char toLowerCase(char c) {
        char baseChar = c;
        if (c < BASE_CHARS.length) {
            baseChar = BASE_CHARS[c];
        }
        if (baseChar >= 'A' && baseChar <= 'Z') {
            return (char)(baseChar | 32);
        } else if (baseChar > 127) {
            return Character.toLowerCase(baseChar);
        }
        return baseChar;
    }

    /**
     * Table mapping most combined Latin, Greek, and Cyrillic characters
     * to their base characters.  If c is in range, BASE_CHARS[c] == c
     * if c is not a combined character, or the base character if it
     * is combined.
     */
    private static final char BASE_CHARS[] = {
        0x0000, 0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007,
        0x0008, 0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x000e, 0x000f,
        0x0010, 0x0011, 0x0012, 0x0013, 0x0014, 0x0015, 0x0016, 0x0017,
        0x0018, 0x0019, 0x001a, 0x001b, 0x001c, 0x001d, 0x001e, 0x001f,
        0x0020, 0x0021, 0x0022, 0x0023, 0x0024, 0x0025, 0x0026, 0x0027,
        0x0028, 0x0029, 0x002a, 0x002b, 0x002c, 0x002d, 0x002e, 0x002f,
        0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037,
        0x0038, 0x0039, 0x003a, 0x003b, 0x003c, 0x003d, 0x003e, 0x003f,
        0x0040, 0x0041, 0x0042, 0x0043, 0x0044, 0x0045, 0x0046, 0x0047,
        0x0048, 0x0049, 0x004a, 0x004b, 0x004c, 0x004d, 0x004e, 0x004f,
        0x0050, 0x0051, 0x0052, 0x0053, 0x0054, 0x0055, 0x0056, 0x0057,
        0x0058, 0x0059, 0x005a, 0x005b, 0x005c, 0x005d, 0x005e, 0x005f,
        0x0060, 0x0061, 0x0062, 0x0063, 0x0064, 0x0065, 0x0066, 0x0067,
        0x0068, 0x0069, 0x006a, 0x006b, 0x006c, 0x006d, 0x006e, 0x006f,
        0x0070, 0x0071, 0x0072, 0x0073, 0x0074, 0x0075, 0x0076, 0x0077,
        0x0078, 0x0079, 0x007a, 0x007b, 0x007c, 0x007d, 0x007e, 0x007f,
        0x0080, 0x0081, 0x0082, 0x0083, 0x0084, 0x0085, 0x0086, 0x0087,
        0x0088, 0x0089, 0x008a, 0x008b, 0x008c, 0x008d, 0x008e, 0x008f,
        0x0090, 0x0091, 0x0092, 0x0093, 0x0094, 0x0095, 0x0096, 0x0097,
        0x0098, 0x0099, 0x009a, 0x009b, 0x009c, 0x009d, 0x009e, 0x009f,
        0x0020, 0x00a1, 0x00a2, 0x00a3, 0x00a4, 0x00a5, 0x00a6, 0x00a7,
        0x0020, 0x00a9, 0x0061, 0x00ab, 0x00ac, 0x00ad, 0x00ae, 0x0020,
        0x00b0, 0x00b1, 0x0032, 0x0033, 0x0020, 0x03bc, 0x00b6, 0x00b7,
        0x0020, 0x0031, 0x006f, 0x00bb, 0x0031, 0x0031, 0x0033, 0x00bf,
        0x0041, 0x0041, 0x0041, 0x0041, 0x0041, 0x0041, 0x00c6, 0x0043,
        0x0045, 0x0045, 0x0045, 0x0045, 0x0049, 0x0049, 0x0049, 0x0049,
        0x00d0, 0x004e, 0x004f, 0x004f, 0x004f, 0x004f, 0x004f, 0x00d7,
        0x004f, 0x0055, 0x0055, 0x0055, 0x0055, 0x0059, 0x00de, 0x0073, // Manually changed d8 to 4f
                                                                        // Manually changed df to 73
        0x0061, 0x0061, 0x0061, 0x0061, 0x0061, 0x0061, 0x00e6, 0x0063,
        0x0065, 0x0065, 0x0065, 0x0065, 0x0069, 0x0069, 0x0069, 0x0069,
        0x00f0, 0x006e, 0x006f, 0x006f, 0x006f, 0x006f, 0x006f, 0x00f7,
        0x006f, 0x0075, 0x0075, 0x0075, 0x0075, 0x0079, 0x00fe, 0x0079, // Manually changed f8 to 6f
        0x0041, 0x0061, 0x0041, 0x0061, 0x0041, 0x0061, 0x0043, 0x0063,
        0x0043, 0x0063, 0x0043, 0x0063, 0x0043, 0x0063, 0x0044, 0x0064,
        0x0110, 0x0111, 0x0045, 0x0065, 0x0045, 0x0065, 0x0045, 0x0065,
        0x0045, 0x0065, 0x0045, 0x0065, 0x0047, 0x0067, 0x0047, 0x0067,
        0x0047, 0x0067, 0x0047, 0x0067, 0x0048, 0x0068, 0x0126, 0x0127,
        0x0049, 0x0069, 0x0049, 0x0069, 0x0049, 0x0069, 0x0049, 0x0069,
        0x0049, 0x0131, 0x0049, 0x0069, 0x004a, 0x006a, 0x004b, 0x006b,
        0x0138, 0x004c, 0x006c, 0x004c, 0x006c, 0x004c, 0x006c, 0x004c,
        0x006c, 0x0141, 0x0142, 0x004e, 0x006e, 0x004e, 0x006e, 0x004e,
        0x006e, 0x02bc, 0x014a, 0x014b, 0x004f, 0x006f, 0x004f, 0x006f,
        0x004f, 0x006f, 0x0152, 0x0153, 0x0052, 0x0072, 0x0052, 0x0072,
        0x0052, 0x0072, 0x0053, 0x0073, 0x0053, 0x0073, 0x0053, 0x0073,
        0x0053, 0x0073, 0x0054, 0x0074, 0x0054, 0x0074, 0x0166, 0x0167,
        0x0055, 0x0075, 0x0055, 0x0075, 0x0055, 0x0075, 0x0055, 0x0075,
        0x0055, 0x0075, 0x0055, 0x0075, 0x0057, 0x0077, 0x0059, 0x0079,
        0x0059, 0x005a, 0x007a, 0x005a, 0x007a, 0x005a, 0x007a, 0x0073,
        0x0180, 0x0181, 0x0182, 0x0183, 0x0184, 0x0185, 0x0186, 0x0187,
        0x0188, 0x0189, 0x018a, 0x018b, 0x018c, 0x018d, 0x018e, 0x018f,
        0x0190, 0x0191, 0x0192, 0x0193, 0x0194, 0x0195, 0x0196, 0x0197,
        0x0198, 0x0199, 0x019a, 0x019b, 0x019c, 0x019d, 0x019e, 0x019f,
        0x004f, 0x006f, 0x01a2, 0x01a3, 0x01a4, 0x01a5, 0x01a6, 0x01a7,
        0x01a8, 0x01a9, 0x01aa, 0x01ab, 0x01ac, 0x01ad, 0x01ae, 0x0055,
        0x0075, 0x01b1, 0x01b2, 0x01b3, 0x01b4, 0x01b5, 0x01b6, 0x01b7,
        0x01b8, 0x01b9, 0x01ba, 0x01bb, 0x01bc, 0x01bd, 0x01be, 0x01bf,
        0x01c0, 0x01c1, 0x01c2, 0x01c3, 0x0044, 0x0044, 0x0064, 0x004c,
        0x004c, 0x006c, 0x004e, 0x004e, 0x006e, 0x0041, 0x0061, 0x0049,
        0x0069, 0x004f, 0x006f, 0x0055, 0x0075, 0x00dc, 0x00fc, 0x00dc,
        0x00fc, 0x00dc, 0x00fc, 0x00dc, 0x00fc, 0x01dd, 0x00c4, 0x00e4,
        0x0226, 0x0227, 0x00c6, 0x00e6, 0x01e4, 0x01e5, 0x0047, 0x0067,
        0x004b, 0x006b, 0x004f, 0x006f, 0x01ea, 0x01eb, 0x01b7, 0x0292,
        0x006a, 0x0044, 0x0044, 0x0064, 0x0047, 0x0067, 0x01f6, 0x01f7,
        0x004e, 0x006e, 0x00c5, 0x00e5, 0x00c6, 0x00e6, 0x00d8, 0x00f8,
        0x0041, 0x0061, 0x0041, 0x0061, 0x0045, 0x0065, 0x0045, 0x0065,
        0x0049, 0x0069, 0x0049, 0x0069, 0x004f, 0x006f, 0x004f, 0x006f,
        0x0052, 0x0072, 0x0052, 0x0072, 0x0055, 0x0075, 0x0055, 0x0075,
        0x0053, 0x0073, 0x0054, 0x0074, 0x021c, 0x021d, 0x0048, 0x0068,
        0x0220, 0x0221, 0x0222, 0x0223, 0x0224, 0x0225, 0x0041, 0x0061,
        0x0045, 0x0065, 0x00d6, 0x00f6, 0x00d5, 0x00f5, 0x004f, 0x006f,
        0x022e, 0x022f, 0x0059, 0x0079, 0x0234, 0x0235, 0x0236, 0x0237,
        0x0238, 0x0239, 0x023a, 0x023b, 0x023c, 0x023d, 0x023e, 0x023f,
        0x0240, 0x0241, 0x0242, 0x0243, 0x0244, 0x0245, 0x0246, 0x0247,
        0x0248, 0x0249, 0x024a, 0x024b, 0x024c, 0x024d, 0x024e, 0x024f,
        0x0250, 0x0251, 0x0252, 0x0253, 0x0254, 0x0255, 0x0256, 0x0257,
        0x0258, 0x0259, 0x025a, 0x025b, 0x025c, 0x025d, 0x025e, 0x025f,
        0x0260, 0x0261, 0x0262, 0x0263, 0x0264, 0x0265, 0x0266, 0x0267,
        0x0268, 0x0269, 0x026a, 0x026b, 0x026c, 0x026d, 0x026e, 0x026f,
        0x0270, 0x0271, 0x0272, 0x0273, 0x0274, 0x0275, 0x0276, 0x0277,
        0x0278, 0x0279, 0x027a, 0x027b, 0x027c, 0x027d, 0x027e, 0x027f,
        0x0280, 0x0281, 0x0282, 0x0283, 0x0284, 0x0285, 0x0286, 0x0287,
        0x0288, 0x0289, 0x028a, 0x028b, 0x028c, 0x028d, 0x028e, 0x028f,
        0x0290, 0x0291, 0x0292, 0x0293, 0x0294, 0x0295, 0x0296, 0x0297,
        0x0298, 0x0299, 0x029a, 0x029b, 0x029c, 0x029d, 0x029e, 0x029f,
        0x02a0, 0x02a1, 0x02a2, 0x02a3, 0x02a4, 0x02a5, 0x02a6, 0x02a7,
        0x02a8, 0x02a9, 0x02aa, 0x02ab, 0x02ac, 0x02ad, 0x02ae, 0x02af,
        0x0068, 0x0266, 0x006a, 0x0072, 0x0279, 0x027b, 0x0281, 0x0077,
        0x0079, 0x02b9, 0x02ba, 0x02bb, 0x02bc, 0x02bd, 0x02be, 0x02bf,
        0x02c0, 0x02c1, 0x02c2, 0x02c3, 0x02c4, 0x02c5, 0x02c6, 0x02c7,
        0x02c8, 0x02c9, 0x02ca, 0x02cb, 0x02cc, 0x02cd, 0x02ce, 0x02cf,
        0x02d0, 0x02d1, 0x02d2, 0x02d3, 0x02d4, 0x02d5, 0x02d6, 0x02d7,
        0x0020, 0x0020, 0x0020, 0x0020, 0x0020, 0x0020, 0x02de, 0x02df,
        0x0263, 0x006c, 0x0073, 0x0078, 0x0295, 0x02e5, 0x02e6, 0x02e7,
        0x02e8, 0x02e9, 0x02ea, 0x02eb, 0x02ec, 0x02ed, 0x02ee, 0x02ef,
        0x02f0, 0x02f1, 0x02f2, 0x02f3, 0x02f4, 0x02f5, 0x02f6, 0x02f7,
        0x02f8, 0x02f9, 0x02fa, 0x02fb, 0x02fc, 0x02fd, 0x02fe, 0x02ff,
        0x0300, 0x0301, 0x0302, 0x0303, 0x0304, 0x0305, 0x0306, 0x0307,
        0x0308, 0x0309, 0x030a, 0x030b, 0x030c, 0x030d, 0x030e, 0x030f,
        0x0310, 0x0311, 0x0312, 0x0313, 0x0314, 0x0315, 0x0316, 0x0317,
        0x0318, 0x0319, 0x031a, 0x031b, 0x031c, 0x031d, 0x031e, 0x031f,
        0x0320, 0x0321, 0x0322, 0x0323, 0x0324, 0x0325, 0x0326, 0x0327,
        0x0328, 0x0329, 0x032a, 0x032b, 0x032c, 0x032d, 0x032e, 0x032f,
        0x0330, 0x0331, 0x0332, 0x0333, 0x0334, 0x0335, 0x0336, 0x0337,
        0x0338, 0x0339, 0x033a, 0x033b, 0x033c, 0x033d, 0x033e, 0x033f,
        0x0300, 0x0301, 0x0342, 0x0313, 0x0308, 0x0345, 0x0346, 0x0347,
        0x0348, 0x0349, 0x034a, 0x034b, 0x034c, 0x034d, 0x034e, 0x034f,
        0x0350, 0x0351, 0x0352, 0x0353, 0x0354, 0x0355, 0x0356, 0x0357,
        0x0358, 0x0359, 0x035a, 0x035b, 0x035c, 0x035d, 0x035e, 0x035f,
        0x0360, 0x0361, 0x0362, 0x0363, 0x0364, 0x0365, 0x0366, 0x0367,
        0x0368, 0x0369, 0x036a, 0x036b, 0x036c, 0x036d, 0x036e, 0x036f,
        0x0370, 0x0371, 0x0372, 0x0373, 0x02b9, 0x0375, 0x0376, 0x0377,
        0x0378, 0x0379, 0x0020, 0x037b, 0x037c, 0x037d, 0x003b, 0x037f,
        0x0380, 0x0381, 0x0382, 0x0383, 0x0020, 0x00a8, 0x0391, 0x00b7,
        0x0395, 0x0397, 0x0399, 0x038b, 0x039f, 0x038d, 0x03a5, 0x03a9,
        0x03ca, 0x0391, 0x0392, 0x0393, 0x0394, 0x0395, 0x0396, 0x0397,
        0x0398, 0x0399, 0x039a, 0x039b, 0x039c, 0x039d, 0x039e, 0x039f,
        0x03a0, 0x03a1, 0x03a2, 0x03a3, 0x03a4, 0x03a5, 0x03a6, 0x03a7,
        0x03a8, 0x03a9, 0x0399, 0x03a5, 0x03b1, 0x03b5, 0x03b7, 0x03b9,
        0x03cb, 0x03b1, 0x03b2, 0x03b3, 0x03b4, 0x03b5, 0x03b6, 0x03b7,
        0x03b8, 0x03b9, 0x03ba, 0x03bb, 0x03bc, 0x03bd, 0x03be, 0x03bf,
        0x03c0, 0x03c1, 0x03c2, 0x03c3, 0x03c4, 0x03c5, 0x03c6, 0x03c7,
        0x03c8, 0x03c9, 0x03b9, 0x03c5, 0x03bf, 0x03c5, 0x03c9, 0x03cf,
        0x03b2, 0x03b8, 0x03a5, 0x03d2, 0x03d2, 0x03c6, 0x03c0, 0x03d7,
        0x03d8, 0x03d9, 0x03da, 0x03db, 0x03dc, 0x03dd, 0x03de, 0x03df,
        0x03e0, 0x03e1, 0x03e2, 0x03e3, 0x03e4, 0x03e5, 0x03e6, 0x03e7,
        0x03e8, 0x03e9, 0x03ea, 0x03eb, 0x03ec, 0x03ed, 0x03ee, 0x03ef,
        0x03ba, 0x03c1, 0x03c2, 0x03f3, 0x0398, 0x03b5, 0x03f6, 0x03f7,
        0x03f8, 0x03a3, 0x03fa, 0x03fb, 0x03fc, 0x03fd, 0x03fe, 0x03ff,
        0x0415, 0x0415, 0x0402, 0x0413, 0x0404, 0x0405, 0x0406, 0x0406,
        0x0408, 0x0409, 0x040a, 0x040b, 0x041a, 0x0418, 0x0423, 0x040f,
        0x0410, 0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417,
        0x0418, 0x0418, 0x041a, 0x041b, 0x041c, 0x041d, 0x041e, 0x041f,
        0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0425, 0x0426, 0x0427,
        0x0428, 0x0429, 0x042a, 0x042b, 0x042c, 0x042d, 0x042e, 0x042f,
        0x0430, 0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0437,
        0x0438, 0x0438, 0x043a, 0x043b, 0x043c, 0x043d, 0x043e, 0x043f,
        0x0440, 0x0441, 0x0442, 0x0443, 0x0444, 0x0445, 0x0446, 0x0447,
        0x0448, 0x0449, 0x044a, 0x044b, 0x044c, 0x044d, 0x044e, 0x044f,
        0x0435, 0x0435, 0x0452, 0x0433, 0x0454, 0x0455, 0x0456, 0x0456,
        0x0458, 0x0459, 0x045a, 0x045b, 0x043a, 0x0438, 0x0443, 0x045f,
        0x0460, 0x0461, 0x0462, 0x0463, 0x0464, 0x0465, 0x0466, 0x0467,
        0x0468, 0x0469, 0x046a, 0x046b, 0x046c, 0x046d, 0x046e, 0x046f,
        0x0470, 0x0471, 0x0472, 0x0473, 0x0474, 0x0475, 0x0474, 0x0475,
        0x0478, 0x0479, 0x047a, 0x047b, 0x047c, 0x047d, 0x047e, 0x047f,
        0x0480, 0x0481, 0x0482, 0x0483, 0x0484, 0x0485, 0x0486, 0x0487,
        0x0488, 0x0489, 0x048a, 0x048b, 0x048c, 0x048d, 0x048e, 0x048f,
        0x0490, 0x0491, 0x0492, 0x0493, 0x0494, 0x0495, 0x0496, 0x0497,
        0x0498, 0x0499, 0x049a, 0x049b, 0x049c, 0x049d, 0x049e, 0x049f,
        0x04a0, 0x04a1, 0x04a2, 0x04a3, 0x04a4, 0x04a5, 0x04a6, 0x04a7,
        0x04a8, 0x04a9, 0x04aa, 0x04ab, 0x04ac, 0x04ad, 0x04ae, 0x04af,
        0x04b0, 0x04b1, 0x04b2, 0x04b3, 0x04b4, 0x04b5, 0x04b6, 0x04b7,
        0x04b8, 0x04b9, 0x04ba, 0x04bb, 0x04bc, 0x04bd, 0x04be, 0x04bf,
        0x04c0, 0x0416, 0x0436, 0x04c3, 0x04c4, 0x04c5, 0x04c6, 0x04c7,
        0x04c8, 0x04c9, 0x04ca, 0x04cb, 0x04cc, 0x04cd, 0x04ce, 0x04cf,
        0x0410, 0x0430, 0x0410, 0x0430, 0x04d4, 0x04d5, 0x0415, 0x0435,
        0x04d8, 0x04d9, 0x04d8, 0x04d9, 0x0416, 0x0436, 0x0417, 0x0437,
        0x04e0, 0x04e1, 0x0418, 0x0438, 0x0418, 0x0438, 0x041e, 0x043e,
        0x04e8, 0x04e9, 0x04e8, 0x04e9, 0x042d, 0x044d, 0x0423, 0x0443,
        0x0423, 0x0443, 0x0423, 0x0443, 0x0427, 0x0447, 0x04f6, 0x04f7,
        0x042b, 0x044b, 0x04fa, 0x04fb, 0x04fc, 0x04fd, 0x04fe, 0x04ff,
    };

    // generated with:
    // cat UnicodeData.txt | perl -e 'while (<>) { @foo = split(/;/); $foo[5] =~ s/<.*> //; $base[hex($foo[0])] = hex($foo[5]);} for ($i = 0; $i < 0x500; $i += 8) { for ($j = $i; $j < $i + 8; $j++) { printf("0x%04x, ", $base[$j] ? $base[$j] : $j)}; print "\n"; }'

}
