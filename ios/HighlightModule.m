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

RCT_EXTERN_METHOD(highlightLocator:(nonnull NSNumber *)reactTag
                  location:(nonnull NSDictionary *)location)

RCT_EXTERN_METHOD(highlightLocatorWithStyle:(nonnull NSNumber *)reactTag
                  location:(nonnull NSDictionary *)location
                  style:(nonnull NSDictionary *)style)

RCT_EXTERN_METHOD(getChapterRawText:(nonnull NSNumber *)reactTag
                  href:(nonnull NSString *)href
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

@end
