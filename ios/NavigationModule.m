#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(NavigationModule, NSObject)

RCT_EXTERN_METHOD(navigateTo:(nonnull NSNumber *)reactTag
                  location:(nonnull NSDictionary *)location
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

@end
