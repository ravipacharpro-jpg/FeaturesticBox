package com.featurestic.box.utils;

import java.util.LinkedList;
import java.util.List;

public class TrieTree {

    private final TrieNode root = new TrieNode();

    private static class TrieNode {
        char content;
        String word;
        boolean isEnd = false;
        List<TrieNode> children = new LinkedList<>();

        public TrieNode() {
        }

        public TrieNode(char content, String word) {
            this.content = content;
            this.word = word;
        }

        @Override
        public boolean equals(Object object) {
            if (object instanceof TrieNode) {
                return ((TrieNode) object).content == content;
            }
            return false;
        }

        public TrieNode nextNode(char content) {
            for (TrieNode childNode : children) {
                if (childNode.content == content)
                    return childNode;
            }
            return null;
        }
    }

    public void add(String word) {
        if (word == null || word.isEmpty()) return;
        TrieNode current = root;
        StringBuilder wordBuilder = new StringBuilder();
        for (int index = 0; index < word.length(); ++index) {
            char content = word.charAt(index);
            wordBuilder.append(content);
            TrieNode node = new TrieNode(content, wordBuilder.toString());
            if (current.children.contains(node)) {
                current = current.nextNode(content);
            } else {
                current.children.add(node);
                current = node;
            }

            if (index == (word.length() - 1)) {
                current.isEnd = true;
            }
        }
    }

    public void addAll(List<String> words) {
        if (words == null) return;
        for (String word : words) {
            add(word);
        }
    }

    /**
     * Returns the LONGEST matching prefix in the Trie that ends on a valid path boundary
     * (exact match, followed by '/', or prefix itself ends with '/'), so specific rules like
     * `/sdcard/Android/obb/<pkg>` and `/data/data/<pkg>/lib` are never shadowed by shorter
     * parent rules like `/sdcard` or `/data/data/<pkg>`.
     */
    public String search(String word) {
        if (word == null || word.isEmpty()) return null;
        TrieNode current = root;
        String longestMatch = null;
        int len = word.length();
        for (int index = 0; index < len; ++index) {
            char content = word.charAt(index);
            TrieNode next = current.nextNode(content);
            if (next == null) {
                break;
            }
            current = next;
            if (current.isEnd && current.word != null) {
                if (index == len - 1
                        || word.charAt(index + 1) == '/'
                        || current.word.endsWith("/")) {
                    longestMatch = current.word;
                }
            }
        }
        return longestMatch;
    }
}
