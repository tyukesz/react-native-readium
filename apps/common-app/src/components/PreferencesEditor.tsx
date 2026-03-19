import React, { useState, useCallback, useEffect } from 'react';
import { Text, ScrollView, TextInput, View, Pressable, StyleSheet } from 'react-native';
import { ListItem, Overlay, Icon, Button } from '@rneui/themed';
import Slider from '@react-native-community/slider';
import type { ReadiumProps } from '@tyukesz/react-native-readium';
import { RANGES } from '@tyukesz/react-native-readium';

interface PreferencesEditorProps {
  preferences: ReadiumProps['preferences'];
  onChange: (preferences: ReadiumProps['preferences']) => void;
}

type Theme = NonNullable<ReadiumProps['preferences']['theme']>;

const overlayStyle = {
  width: '90%',
  marginVertical: 100,
} as const;

const COLOR_PRESETS = ['#FFFFFF', '#121212', '#FAF4E8', '#2877B7', '#B73B28', '#E1FF00'] as const;

const HEX_COLOR_PATTERN = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;

function normalizeColorInput(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }

  const prefixed = trimmed.startsWith('#') ? trimmed : `#${trimmed}`;
  if (!HEX_COLOR_PATTERN.test(prefixed)) {
    return null;
  }

  return prefixed.toUpperCase();
}

interface ColorFieldProps {
  label: string;
  value?: string | null;
  draft: string;
  onDraftChange: (value: string) => void;
  onApply: (value: string | null) => void;
}

const ColorField: React.FC<ColorFieldProps> = ({
  label,
  value,
  draft,
  onDraftChange,
  onApply,
}) => {
  const normalizedValue = value?.toUpperCase() ?? null;
  const normalizedDraft = normalizeColorInput(draft);
  const hasDraft = draft.trim().length > 0;
  const isDraftValid = !hasDraft || normalizedDraft !== null;

  return (
    <ListItem>
      <ListItem.Content>
        <ListItem.Title>{label}</ListItem.Title>
        <Text style={styles.colorValueText}>{value ?? 'theme default'}</Text>

        <View style={styles.swatchRow}>
          {COLOR_PRESETS.map((preset) => {
            const isSelected = normalizedValue === preset;

            return (
              <Pressable
                key={`${label}-${preset}`}
                onPress={() => {
                  onDraftChange(preset);
                  onApply(preset);
                }}
                style={[
                  styles.swatch,
                  { backgroundColor: preset },
                  isSelected ? styles.swatchSelected : null,
                ]}
              >
                <Text style={styles.swatchLabel}>{isSelected ? '✓' : ''}</Text>
              </Pressable>
            );
          })}
        </View>

        <View style={styles.inputRow}>
          <TextInput
            value={draft}
            onChangeText={onDraftChange}
            placeholder="#B73B28"
            placeholderTextColor="#777"
            autoCapitalize="characters"
            autoCorrect={false}
            style={styles.colorInput}
          />
          <Button
            title="Apply"
            disabled={!isDraftValid || normalizedDraft === normalizedValue}
            onPress={() => {
              if (normalizedDraft) {
                onDraftChange(normalizedDraft);
              }
              onApply(normalizedDraft);
            }}
          />
          <Button
            type="outline"
            title="Clear"
            onPress={() => {
              onDraftChange('');
              onApply(null);
            }}
          />
        </View>

        {!isDraftValid ? (
          <Text style={styles.validationText}>Valid forms: #RGB, #RRGGBB, #RRGGBBAA</Text>
        ) : null}
      </ListItem.Content>
    </ListItem>
  );
};

export const PreferencesEditor: React.FC<PreferencesEditorProps> = ({
  preferences,
  onChange,
}) => {
  const [isOpen, setIsOpen] = useState<boolean>(false);
  const [backgroundDraft, setBackgroundDraft] = useState(
    preferences.backgroundColor ?? ''
  );
  const [textDraft, setTextDraft] = useState(preferences.textColor ?? '');

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    setBackgroundDraft(preferences.backgroundColor ?? '');
    setTextDraft(preferences.textColor ?? '');
  }, [isOpen, preferences.backgroundColor, preferences.textColor]);

  const onToggleOpen = useCallback(() => setIsOpen((prev) => !prev), []);
  const nextAppearance = useCallback((theme?: Theme) => {
    if (theme === 'light') {
      return 'dark';
    } else if (theme === 'dark') {
      return 'sepia';
    } else {
      return 'light';
    }
  }, []);

  return (
    <>
      <Icon name="gear" type="font-awesome" size={30} onPress={onToggleOpen} />
      <Overlay
        isVisible={isOpen}
        onBackdropPress={onToggleOpen}
        overlayStyle={overlayStyle}
      >
        <ScrollView>
          <Text>Preferences</Text>
          <ListItem>
            <ListItem.Content>
              <ListItem.Title>Theme</ListItem.Title>
            </ListItem.Content>
            <Button
              title={preferences.theme}
              onPress={() => {
                onChange({
                  ...preferences,
                  theme: nextAppearance(preferences.theme),
                });
              }}
            />
          </ListItem>

          <ListItem>
            <ListItem.Content>
              <ListItem.Title>Font Size</ListItem.Title>
              <Slider
                style={{ width: '100%' }}
                minimumValue={RANGES.fontSize[0]}
                maximumValue={RANGES.fontSize[1]}
                step={0.1}
                value={preferences.fontSize}
                onSlidingComplete={(fontSize: number) => {
                  onChange({
                    ...preferences,
                    fontSize,
                    typeScale: fontSize,
                  });
                }}
                minimumTrackTintColor="#cccccc"
                maximumTrackTintColor="#aaaaaa"
              />
            </ListItem.Content>
          </ListItem>

          <ListItem>
            <ListItem.Content>
              <ListItem.Title>Page Margin</ListItem.Title>
              <Slider
                style={{ width: '100%' }}
                minimumValue={RANGES.pageMargins[0]}
                maximumValue={RANGES.pageMargins[1]}
                step={1}
                value={preferences.pageMargins}
                onSlidingComplete={(pageMargins: number) => {
                  onChange({
                    ...preferences,
                    pageMargins,
                  });
                }}
                minimumTrackTintColor="#cccccc"
                maximumTrackTintColor="#aaaaaa"
              />
            </ListItem.Content>
          </ListItem>

          <ColorField
            label="Background color"
            value={preferences.backgroundColor}
            draft={backgroundDraft}
            onDraftChange={setBackgroundDraft}
            onApply={(backgroundColor) => {
              onChange({
                ...preferences,
                backgroundColor,
              });
            }}
          />

          <ColorField
            label="Text color"
            value={preferences.textColor}
            draft={textDraft}
            onDraftChange={setTextDraft}
            onApply={(textColor) => {
              onChange({
                ...preferences,
                textColor,
              });
            }}
          />
        </ScrollView>
      </Overlay>
    </>
  );
};

const styles = StyleSheet.create({
  colorValueText: {
    marginTop: 4,
    marginBottom: 10,
    color: '#666',
  },
  swatchRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    marginBottom: 12,
  },
  swatch: {
    width: 32,
    height: 32,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#999',
    alignItems: 'center',
    justifyContent: 'center',
  },
  swatchSelected: {
    borderWidth: 3,
    borderColor: '#111',
  },
  swatchLabel: {
    color: '#111',
    fontWeight: '700',
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  colorInput: {
    flex: 1,
    minHeight: 40,
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    paddingHorizontal: 12,
    color: '#111',
  },
  validationText: {
    marginTop: 8,
    color: '#b73b28',
    fontSize: 12,
  },
});
