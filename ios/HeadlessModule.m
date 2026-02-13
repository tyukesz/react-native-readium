#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(HeadlessModule, NSObject)

RCT_EXTERN_METHOD(openPublicationHeadless:(nonnull NSDictionary *)input
                  resolver:(RCTPromiseResolveBlock)resolver
                  rejecter:(RCTPromiseRejectBlock)rejecter)

RCT_EXTERN_METHOD(cancelHeadless:(nonnull NSString *)id)

@end
