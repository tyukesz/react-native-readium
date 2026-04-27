#import "ReadiumViewComponentView.h"

#import <react/renderer/components/RNReadiumSpec/ComponentDescriptors.h>
#import <react/renderer/components/RNReadiumSpec/EventEmitters.h>
#import <react/renderer/components/RNReadiumSpec/Props.h>
#import <react/renderer/components/RNReadiumSpec/RCTComponentViewHelpers.h>

#import "react_native_readium-Swift.h"

using namespace facebook::react;

@interface ReadiumViewComponentView () <RCTReadiumViewViewProtocol>
@end

@implementation ReadiumViewComponentView {
  ReadiumView *_view;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider
{
  return concreteComponentDescriptorProvider<ReadiumViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if ((self = [super initWithFrame:frame])) {
    static const auto defaultProps = std::make_shared<const ReadiumViewProps>();
    _props = defaultProps;

    _view = [[ReadiumView alloc] init];
    _view.frame = self.bounds;
    _view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self addSubview:_view];

    [self wireEvents];
  }
  return self;
}

- (void)wireEvents
{
  __weak __typeof(self) weakSelf = self;

  _view.onLocationChange = ^(NSDictionary *locator) {
    __strong __typeof(weakSelf) strongSelf = weakSelf;
    if (!strongSelf || !strongSelf->_eventEmitter) return;
    NSString *json = [strongSelf jsonStringFromDictionary:locator];
    auto emitter = std::static_pointer_cast<const ReadiumViewEventEmitter>(strongSelf->_eventEmitter);
    emitter->onLocationChange({ .locatorJson = std::string([json UTF8String]) });
  };

  _view.onPublicationReady = ^(NSDictionary *payload) {
    __strong __typeof(weakSelf) strongSelf = weakSelf;
    if (!strongSelf || !strongSelf->_eventEmitter) return;
    NSString *json = [strongSelf jsonStringFromDictionary:payload];
    auto emitter = std::static_pointer_cast<const ReadiumViewEventEmitter>(strongSelf->_eventEmitter);
    emitter->onPublicationReady({ .payloadJson = std::string([json UTF8String]) });
  };

  _view.onRestrictedNavigation = ^(NSDictionary *payload) {
    __strong __typeof(weakSelf) strongSelf = weakSelf;
    if (!strongSelf || !strongSelf->_eventEmitter) return;
    NSString *href = payload[@"href"] ?: @"";
    auto emitter = std::static_pointer_cast<const ReadiumViewEventEmitter>(strongSelf->_eventEmitter);
    emitter->onRestrictedNavigation({ .href = std::string([href UTF8String]) });
  };

  _view.onTap = ^(NSDictionary *payload) {
    __strong __typeof(weakSelf) strongSelf = weakSelf;
    if (!strongSelf || !strongSelf->_eventEmitter) return;
    double x = [payload[@"x"] doubleValue];
    double y = [payload[@"y"] doubleValue];
    auto emitter = std::static_pointer_cast<const ReadiumViewEventEmitter>(strongSelf->_eventEmitter);
    emitter->onTap({ .x = x, .y = y });
  };
}

- (NSString *)jsonStringFromDictionary:(NSDictionary *)dict
{
  if (!dict) return @"";
  NSError *error = nil;
  NSData *data = [NSJSONSerialization dataWithJSONObject:dict options:0 error:&error];
  if (error || !data) return @"";
  return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"";
}

- (void)updateProps:(const Props::Shared &)props oldProps:(const Props::Shared &)oldProps
{
  static const auto emptyProps = std::make_shared<const ReadiumViewProps>();
  const auto &newProps = *std::static_pointer_cast<const ReadiumViewProps>(props);
  const auto &old = oldProps ? *std::static_pointer_cast<const ReadiumViewProps>(oldProps) : *emptyProps;

  if (newProps.file != old.file) {
    _view.file = [NSString stringWithUTF8String:newProps.file.c_str()];
  }
  if (newProps.location != old.location) {
    _view.location = newProps.location.empty() ? nil : [NSString stringWithUTF8String:newProps.location.c_str()];
  }
  if (newProps.preferences != old.preferences) {
    _view.preferences = newProps.preferences.empty() ? nil : [NSString stringWithUTF8String:newProps.preferences.c_str()];
  }
  if (newProps.allowedHrefs != old.allowedHrefs) {
    _view.allowedHrefs = newProps.allowedHrefs.empty() ? nil : [NSString stringWithUTF8String:newProps.allowedHrefs.c_str()];
  }
  if (newProps.paywallHTML != old.paywallHTML) {
    _view.paywallHTML = newProps.paywallHTML.empty() ? nil : [NSString stringWithUTF8String:newProps.paywallHTML.c_str()];
  }
  if (newProps.hidePageNumbers != old.hidePageNumbers) {
    _view.hidePageNumbers = newProps.hidePageNumbers;
  }
  if (newProps.enableTapNavigation != old.enableTapNavigation) {
    _view.enableTapNavigation = newProps.enableTapNavigation;
  }
  if (newProps.disableTextSelection != old.disableTextSelection) {
    _view.disableTextSelection = newProps.disableTextSelection;
  }

  [super updateProps:props oldProps:oldProps];
}

- (void)handleCommand:(const NSString *)commandName args:(const NSArray *)args
{
  RCTReadiumViewHandleCommand(self, commandName, args);
}

- (void)create
{
  [_view handleCreate];
}

@end

Class<RCTComponentViewProtocol> ReadiumViewCls(void)
{
  return ReadiumViewComponentView.class;
}
