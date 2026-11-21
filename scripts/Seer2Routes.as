package {
    import flash.display.Loader;
    import flash.display.Sprite;
    import flash.events.Event;
    import flash.events.IOErrorEvent;
    import flash.external.ExternalInterface;
    import flash.net.URLRequest;
    import flash.system.ApplicationDomain;
    import flash.system.LoaderContext;

    [SWF(width="1200", height="660", frameRate="30")]
    public final class Seer2Routes extends Sprite {
        private static const SCENE:String = "com.taomee.seer2.core.scene.SceneManager";
        private static const SCENE_TYPE:String = "com.taomee.seer2.core.scene.SceneType";
        private static const MAP_LIMIT:String = "com.taomee.seer2.app.gameRule.util.MapLimitUtil";
        private static const MODULES:String = "com.taomee.seer2.core.module.ModuleManager";

        private const game:Loader = new Loader();

        public function Seer2Routes() {
            if (ExternalInterface.available) {
                ExternalInterface.addCallback("seer2OpenRoute", openRoute);
            }
            game.contentLoaderInfo.addEventListener(Event.INIT, onGameInit);
            game.contentLoaderInfo.addEventListener(IOErrorEvent.IO_ERROR, onGameLoadError);
            addChild(game);
            game.load(new URLRequest("https://flash.local/_seer2/Client.swf"),
                new LoaderContext(false, new ApplicationDomain(ApplicationDomain.currentDomain)));
        }

        private function onGameInit(event:Event):void {
            if (ExternalInterface.available) ExternalInterface.call("seer2GameLoadReady");
        }

        private function onGameLoadError(event:IOErrorEvent):void {
            if (ExternalInterface.available) ExternalInterface.call("seer2GameLoadFailed");
        }

        private function openRoute(route:String):String {
            if (route != "vip-park" && route != "king-ruins" && route != "legend") return "unavailable";
            try {
                var domain:ApplicationDomain = game.contentLoaderInfo.applicationDomain;
                if (!domain || !domain.hasDefinition(SCENE)) return "not-ready";
                var scenes:Object = domain.getDefinition(SCENE);
                if (!scenes.active || !scenes.active.mapID) return "not-ready";
                if (route == "legend") {
                    var modules:Object = domain.getDefinition(MODULES);
                    modules.showAppModule("YueseCraft2016MainPanel", {tab: 5});
                    return "requested";
                }

                var mapId:int = route == "vip-park" ? 8011 : 80146;
                if (scenes.active.mapID == mapId) return "already-there";
                var mapRules:Object = domain.getDefinition(MAP_LIMIT);
                if (!mapRules.limit_map81(mapId)) return "restricted";
                var types:Object = domain.getDefinition(SCENE_TYPE);
                if (route == "vip-park") {
                    scenes.changeScene(types.LOBBY, mapId, 0, 0, false, 2);
                } else {
                    scenes.changeScene(types.COPY, mapId);
                }
                return "requested";
            } catch (error:Error) {
                // Legacy modules can be absent while the world is changing.
            }
            return "unavailable";
        }
    }
}
