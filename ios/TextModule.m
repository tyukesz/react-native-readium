#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(TextModule, NSObject)

RCT_EXTERN_METHOD(getVisibleTextRange:(nonnull NSNumber *)reactTag
                  options:(nullable NSDictionary *)options
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

@end
