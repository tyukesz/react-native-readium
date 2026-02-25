import React from 'react';
import {
  Modal,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

export type VisibleRange = {
  href: string;
  start: number;
  end: number;
  totalChars: number;
};

export type SentencePreviewItem = {
  index: number;
  text: string;
  progression?: number;
};

interface HighlightModalProps {
  visible: boolean;
  isNative: boolean;
  highlightHref: string;
  sentenceIndexText: string;
  progressionText: string;
  sentenceCount: number | null;
  isLoadingSentences: boolean;
  isLoadingPreview: boolean;
  visibleRange: VisibleRange | null;
  sentencePreview: SentencePreviewItem[] | null;
  onClose: () => void;
  onApply: () => void;
  onApplyLocator: () => void;
  onClear: () => void;
  onChangeHighlightHref: (value: string) => void;
  onChangeSentenceIndexText: (value: string) => void;
  onChangeProgressionText: (value: string) => void;
  onUseCurrentChapter: () => void;
  onLoadSentences: () => void;
  onLoadPreview: () => void;
  onJumpToProgression: () => void;
}

export const HighlightModal: React.FC<HighlightModalProps> = ({
  visible,
  isNative,
  highlightHref,
  sentenceIndexText,
  progressionText,
  sentenceCount,
  isLoadingSentences,
  isLoadingPreview,
  visibleRange,
  sentencePreview,
  onClose,
  onApply,
  onApplyLocator,
  onClear,
  onChangeHighlightHref,
  onChangeSentenceIndexText,
  onChangeProgressionText,
  onUseCurrentChapter,
  onLoadSentences,
  onLoadPreview,
  onJumpToProgression,
}) => {
  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={onClose}
    >
      <View style={styles.modalBackdrop}>
        <View style={styles.modalCard}>
          <Text style={styles.modalTitle}>Highlight sentence</Text>

          <Text style={styles.modalLabel}>Chapter href</Text>
          <TextInput
            value={highlightHref}
            onChangeText={onChangeHighlightHref}
            placeholder="e.g. /OPS/chapter-1.xhtml"
            placeholderTextColor="#777"
            autoCapitalize="none"
            autoCorrect={false}
            style={styles.modalInput}
          />

          <View style={styles.modalRowLeft}>
            <Pressable onPress={onUseCurrentChapter} style={styles.modalChip}>
              <Text style={styles.modalChipText}>Use current chapter</Text>
            </Pressable>

            <Pressable
              onPress={onLoadSentences}
              style={styles.modalChip}
              disabled={isLoadingSentences || !isNative}
            >
              <Text style={styles.modalChipText}>
                {isLoadingSentences
                  ? 'Loading…'
                  : !isNative
                  ? 'Sentences (Native only)'
                  : `Sentences: ${sentenceCount ?? '—'}`}
              </Text>
            </Pressable>

            <Pressable
              onPress={onLoadPreview}
              style={styles.modalChip}
              disabled={isLoadingPreview || !isNative}
            >
              <Text style={styles.modalChipText}>
                {!isNative
                  ? 'Visible range (Native only)'
                  : isLoadingPreview
                  ? 'Loading…'
                  : 'Get visible range'}
              </Text>
            </Pressable>
          </View>

          <Text style={styles.modalHint}>
            Total sentences: {sentenceCount ?? '—'}
          </Text>

          {visibleRange ? (
            <Text style={styles.modalHint}>
              Visible range: {visibleRange.start}..{visibleRange.end} /{' '}
              {visibleRange.totalChars} ({visibleRange.href})
            </Text>
          ) : null}

          {sentencePreview?.length ? (
            <Text style={styles.modalPreview}>
              {sentencePreview.map((s) => `${s.index}: ${s.text}`).join('\n\n')}
            </Text>
          ) : null}

          <Text style={styles.modalLabel}>Sentence index (0-based)</Text>
          <TextInput
            value={sentenceIndexText}
            onChangeText={onChangeSentenceIndexText}
            keyboardType="number-pad"
            style={styles.modalInput}
          />

          <Text style={styles.modalLabel}>Start from progression (0..1)</Text>
          <TextInput
            value={progressionText}
            onChangeText={onChangeProgressionText}
            keyboardType="decimal-pad"
            style={styles.modalInput}
          />

          <View style={styles.modalRowLeft}>
            <Pressable
              onPress={onJumpToProgression}
              style={styles.modalChip}
              disabled={!isNative}
            >
              <Text style={styles.modalChipText}>Jump to %</Text>
            </Pressable>
          </View>

          <View style={styles.modalRow}>
            <Pressable onPress={onClear} style={styles.modalButton}>
              <Text style={styles.actionButtonText}>Clear</Text>
            </Pressable>
            <Pressable onPress={onClose} style={styles.modalButton}>
              <Text style={styles.actionButtonText}>Cancel</Text>
            </Pressable>
            <Pressable
              onPress={onApply}
              style={[styles.modalButton, styles.modalButtonPrimary]}
            >
              <Text style={styles.modalPrimaryButtonText}>Highlight (Index)</Text>
            </Pressable>
            <Pressable onPress={onApplyLocator} style={styles.modalButton}>
              <Text style={styles.actionButtonText}>Highlight (Locator)</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.6)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 16,
  },
  modalCard: {
    width: '100%',
    maxWidth: 520,
    borderRadius: 12,
    backgroundColor: '#1f1f1f',
    borderWidth: 1,
    borderColor: '#333',
    padding: 14,
  },
  modalTitle: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 10,
  },
  modalLabel: {
    color: '#bbb',
    marginTop: 10,
    marginBottom: 6,
  },
  modalHint: {
    color: '#8a8a8a',
    marginTop: 8,
    fontSize: 12,
  },
  modalPreview: {
    color: '#ddd',
    marginTop: 10,
    fontSize: 12,
    lineHeight: 16,
  },
  modalInput: {
    color: '#fff',
    backgroundColor: '#151515',
    borderWidth: 1,
    borderColor: '#2a2a2a',
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  modalRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    flexWrap: 'wrap',
    gap: 10,
    marginTop: 14,
  },
  modalRowLeft: {
    flexDirection: 'row',
    justifyContent: 'flex-start',
    marginTop: 10,
  },
  modalChip: {
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 999,
    backgroundColor: '#2b2b2b',
    borderWidth: 1,
    borderColor: '#444',
  },
  modalChipText: {
    color: '#fff',
    fontSize: 12,
  },
  modalButton: {
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 8,
    backgroundColor: '#2b2b2b',
    borderWidth: 1,
    borderColor: '#444',
  },
  actionButtonText: {
    color: '#fff',
  },
  modalButtonPrimary: {
    backgroundColor: '#ffd400',
    borderColor: '#ffd400',
  },
  modalPrimaryButtonText: {
    color: '#000',
    fontSize: 14,
    fontWeight: '600',
  },
});
