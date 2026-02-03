#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(HighlightModule, NSObject)

RCT_EXTERN_METHOD(highlightRange:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  startProgression:(nonnull NSNumber *)startProgression
                  endProgression:(nonnull NSNumber *)endProgression)

RCT_EXTERN_METHOD(highlightRangeWithStyle:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  startProgression:(nonnull NSNumber *)startProgression
                  endProgression:(nonnull NSNumber *)endProgression
                  style:(nonnull NSDictionary *)style)

RCT_EXTERN_METHOD(clearHighlight:(nonnull NSNumber *)reactTag)

RCT_EXTERN_METHOD(highlightSentence:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  sentenceIndex:(nonnull NSNumber *)sentenceIndex)

RCT_EXTERN_METHOD(highlightSentenceWithStyle:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  sentenceIndex:(nonnull NSNumber *)sentenceIndex
                  style:(nonnull NSDictionary *)style)

RCT_EXTERN_METHOD(getChapterSentencePage:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  offset:(nonnull NSNumber *)offset
                  limit:(nonnull NSNumber *)limit
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

RCT_EXTERN_METHOD(getChapterSentences:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

RCT_EXTERN_METHOD(getSentenceIndexFromProgression:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  progression:(nonnull NSNumber *)progression
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

RCT_EXTERN_METHOD(highlightSentenceFromProgression:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  progression:(nonnull NSNumber *)progression
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

RCT_EXTERN_METHOD(highlightSentenceFromProgressionWithStyle:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  progression:(nonnull NSNumber *)progression
                  style:(nonnull NSDictionary *)style
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

@end
