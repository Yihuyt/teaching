(() => {
  // scripts/blockcoding/js-reference/vendor/scratchblocks/extensions.js
  var movedExtensions = {
    pen: "pen",
    video: "sensing",
    music: "sound"
  };
  var extensions = {
    ...movedExtensions,
    faceSensing: "faceSensing",
    tts: "tts",
    translate: "translate",
    microbit: "microbit",
    gdxfor: "gdxfor",
    wedo: "wedo",
    makeymakey: "makeymakey",
    ev3: "ev3",
    boost: "boost"
  };
  var aliasExtensions = {
    wedo2: "wedo",
    text2speech: "tts"
  };

  // scripts/blockcoding/js-reference/vendor/scratchblocks/commands.js
  var commands_default = [
    {
      id: "MOTION_MOVESTEPS",
      selector: "forward:",
      spec: "move %1 steps",
      inputs: ["%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_TURNRIGHT",
      selector: "turnRight:",
      spec: "turn @turnRight %1 degrees",
      inputs: ["%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_TURNLEFT",
      selector: "turnLeft:",
      spec: "turn @turnLeft %1 degrees",
      inputs: ["%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_POINTINDIRECTION",
      selector: "heading:",
      spec: "point in direction %1",
      inputs: ["%d.direction"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_POINTTOWARDS",
      selector: "pointTowards:",
      spec: "point towards %1",
      inputs: ["%m.spriteOrMouse"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_GOTOXY",
      selector: "gotoX:y:",
      spec: "go to x:%1 y:%2",
      inputs: ["%n", "%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_GOTO",
      selector: "gotoSpriteOrMouse:",
      spec: "go to %1",
      inputs: ["%m.location"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_GLIDESECSTOXY",
      selector: "glideSecs:toX:y:elapsed:from:",
      spec: "glide %1 secs to x:%2 y:%3",
      inputs: ["%n", "%n", "%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_GLIDETO",
      spec: "glide %1 secs to %2",
      inputs: ["%n", "%m.location"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_CHANGEXBY",
      selector: "changeXposBy:",
      spec: "change x by %1",
      inputs: ["%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_SETX",
      selector: "xpos:",
      spec: "set x to %1",
      inputs: ["%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_CHANGEYBY",
      selector: "changeYposBy:",
      spec: "change y by %1",
      inputs: ["%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_SETY",
      selector: "ypos:",
      spec: "set y to %1",
      inputs: ["%n"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "MOTION_SETROTATIONSTYLE",
      selector: "setRotationStyle",
      spec: "set rotation style %1",
      inputs: ["%m.rotationStyle"],
      shape: "stack",
      category: "motion"
    },
    {
      id: "LOOKS_SAYFORSECS",
      selector: "say:duration:elapsed:from:",
      spec: "say %1 for %2 seconds",
      inputs: ["%s", "%n"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_SAY",
      selector: "say:",
      spec: "say %1",
      inputs: ["%s"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_THINKFORSECS",
      selector: "think:duration:elapsed:from:",
      spec: "think %1 for %2 seconds",
      inputs: ["%s", "%n"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_THINK",
      selector: "think:",
      spec: "think %1",
      inputs: ["%s"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_SHOW",
      selector: "show",
      spec: "show",
      inputs: [],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_HIDE",
      selector: "hide",
      spec: "hide",
      inputs: [],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_SWITCHCOSTUMETO",
      selector: "lookLike:",
      spec: "switch costume to %1",
      inputs: ["%m.costume"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_NEXTCOSTUME",
      selector: "nextCostume",
      spec: "next costume",
      inputs: [],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_NEXTBACKDROP_BLOCK",
      selector: "nextScene",
      spec: "next backdrop",
      inputs: [],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_SWITCHBACKDROPTO",
      selector: "startScene",
      spec: "switch backdrop to %1",
      inputs: ["%m.backdrop"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_SWITCHBACKDROPTOANDWAIT",
      selector: "startSceneAndWait",
      spec: "switch backdrop to %1 and wait",
      inputs: ["%m.backdrop"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_CHANGEEFFECTBY",
      selector: "changeGraphicEffect:by:",
      spec: "change %1 effect by %2",
      inputs: ["%m.effect", "%n"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_SETEFFECTTO",
      selector: "setGraphicEffect:to:",
      spec: "set %1 effect to %2",
      inputs: ["%m.effect", "%n"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_CLEARGRAPHICEFFECTS",
      selector: "filterReset",
      spec: "clear graphic effects",
      inputs: [],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_CHANGESIZEBY",
      selector: "changeSizeBy:",
      spec: "change size by %1",
      inputs: ["%n"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_SETSIZETO",
      selector: "setSizeTo:",
      spec: "set size to %1%",
      inputs: ["%n"],
      shape: "stack",
      category: "looks"
    },
    {
      selector: "comeToFront",
      spec: "go to front",
      inputs: [],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_GOTOFRONTBACK",
      spec: "go to %1 layer",
      inputs: ["%m"],
      shape: "stack",
      category: "looks"
    },
    {
      selector: "goBackByLayers:",
      spec: "go back %1 layers",
      inputs: ["%n"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "LOOKS_GOFORWARDBACKWARDLAYERS",
      spec: "go %1 %2 layers",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "looks"
    },
    {
      id: "SOUND_PLAY",
      selector: "playSound:",
      spec: "start sound %1",
      inputs: ["%m.sound"],
      shape: "stack",
      category: "sound"
    },
    {
      id: "SOUND_CHANGEEFFECTBY",
      spec: "change %1 effect by %2",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "sound"
    },
    {
      id: "SOUND_SETEFFECTO",
      // sic
      spec: "set %1 effect to %2",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "sound"
    },
    {
      id: "SOUND_CLEAREFFECTS",
      spec: "clear sound effects",
      inputs: [],
      shape: "stack",
      category: "sound"
    },
    {
      id: "SOUND_PLAYUNTILDONE",
      selector: "doPlaySoundAndWait",
      spec: "play sound %1 until done",
      inputs: ["%m.sound"],
      shape: "stack",
      category: "sound"
    },
    {
      id: "SOUND_STOPALLSOUNDS",
      selector: "stopAllSounds",
      spec: "stop all sounds",
      inputs: [],
      shape: "stack",
      category: "sound"
    },
    {
      id: "music.playDrumForBeats",
      selector: "playDrum",
      spec: "play drum %1 for %2 beats",
      inputs: ["%d.drum", "%n"],
      shape: "stack",
      category: "music"
    },
    {
      id: "music.restForBeats",
      selector: "rest:elapsed:from:",
      spec: "rest for %1 beats",
      inputs: ["%n"],
      shape: "stack",
      category: "music"
    },
    {
      id: "music.playNoteForBeats",
      selector: "noteOn:duration:elapsed:from:",
      spec: "play note %1 for %2 beats",
      inputs: ["%d.note", "%n"],
      shape: "stack",
      category: "music"
    },
    {
      id: "music.setInstrument",
      selector: "instrument:",
      spec: "set instrument to %1",
      inputs: ["%d.instrument"],
      shape: "stack",
      category: "music"
    },
    {
      id: "SOUND_CHANGEVOLUMEBY",
      selector: "changeVolumeBy:",
      spec: "change volume by %1",
      inputs: ["%n"],
      shape: "stack",
      category: "sound"
    },
    {
      id: "SOUND_SETVOLUMETO",
      selector: "setVolumeTo:",
      spec: "set volume to %1%",
      inputs: ["%n"],
      shape: "stack",
      category: "sound"
    },
    {
      id: "music.changeTempo",
      selector: "changeTempoBy:",
      spec: "change tempo by %1",
      inputs: ["%n"],
      shape: "stack",
      category: "music"
    },
    {
      selector: "setTempoTo:",
      spec: "set tempo to %1 bpm",
      inputs: ["%n"],
      shape: "stack",
      category: "sound"
    },
    {
      id: "music.setTempo",
      selector: "setTempoTo:",
      spec: "set tempo to %1",
      inputs: ["%n"],
      shape: "stack",
      category: "music"
    },
    {
      id: "pen.clear",
      selector: "clearPenTrails",
      spec: "erase all",
      inputs: [],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.stamp",
      selector: "stampCostume",
      spec: "stamp",
      inputs: [],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.penDown",
      selector: "putPenDown",
      spec: "pen down",
      inputs: [],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.penUp",
      selector: "putPenUp",
      spec: "pen up",
      inputs: [],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.setColor",
      selector: "penColor:",
      spec: "set pen color to %1",
      inputs: ["%c"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.changeHue",
      selector: "changePenHueBy:",
      spec: "change pen color by %1",
      inputs: ["%n"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.setColorParam",
      spec: "set pen %1 to %2",
      inputs: ["%m.color", "%c"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.changeColorParam",
      spec: "change pen %1 by %2",
      inputs: ["%m.color", "%n"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.setHue",
      selector: "setPenHueTo:",
      spec: "set pen color to %1",
      inputs: ["%n"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.changeShade",
      selector: "changePenShadeBy:",
      spec: "change pen shade by %1",
      inputs: ["%n"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.setShade",
      selector: "setPenShadeTo:",
      spec: "set pen shade to %1",
      inputs: ["%n"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.changeSize",
      selector: "changePenSizeBy:",
      spec: "change pen size by %1",
      inputs: ["%n"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "pen.setSize",
      selector: "penSize:",
      spec: "set pen size to %1",
      inputs: ["%n"],
      shape: "stack",
      category: "pen"
    },
    {
      id: "EVENT_WHENFLAGCLICKED",
      selector: "whenGreenFlag",
      spec: "when @greenFlag clicked",
      inputs: [],
      shape: "hat",
      category: "events"
    },
    {
      id: "EVENT_WHENKEYPRESSED",
      selector: "whenKeyPressed",
      spec: "when %1 key pressed",
      inputs: ["%m.key"],
      shape: "hat",
      category: "events"
    },
    {
      id: "EVENT_WHENTHISSPRITECLICKED",
      selector: "whenClicked",
      spec: "when this sprite clicked",
      inputs: [],
      shape: "hat",
      category: "events"
    },
    {
      id: "EVENT_WHENSTAGECLICKED",
      spec: "when stage clicked",
      inputs: [],
      shape: "hat",
      category: "events"
    },
    {
      id: "EVENT_WHENBACKDROPSWITCHESTO",
      selector: "whenSceneStarts",
      spec: "when backdrop switches to %1",
      inputs: ["%m.backdrop"],
      shape: "hat",
      category: "events"
    },
    {
      id: "EVENT_WHENGREATERTHAN",
      selector: "whenSensorGreaterThan",
      spec: "when %1 > %2",
      inputs: ["%m.triggerSensor", "%n"],
      shape: "hat",
      category: "events"
    },
    {
      id: "EVENT_WHENBROADCASTRECEIVED",
      selector: "whenIReceive",
      spec: "when I receive %1",
      inputs: ["%m.broadcast"],
      shape: "hat",
      category: "events"
    },
    {
      id: "EVENT_BROADCAST",
      selector: "broadcast:",
      spec: "broadcast %1",
      inputs: ["%m.broadcast"],
      shape: "stack",
      category: "events"
    },
    {
      id: "EVENT_BROADCASTANDWAIT",
      selector: "doBroadcastAndWait",
      spec: "broadcast %1 and wait",
      inputs: ["%m.broadcast"],
      shape: "stack",
      category: "events"
    },
    {
      id: "CONTROL_WAIT",
      selector: "wait:elapsed:from:",
      spec: "wait %1 seconds",
      inputs: ["%n"],
      shape: "stack",
      category: "control"
    },
    {
      id: "CONTROL_REPEAT",
      selector: "doRepeat",
      spec: "repeat %1",
      inputs: ["%n"],
      shape: "c-block",
      category: "control",
      hasLoopArrow: true
    },
    {
      id: "CONTROL_FOREVER",
      selector: "doForever",
      spec: "forever",
      inputs: [],
      shape: "c-block cap",
      category: "control",
      hasLoopArrow: true
    },
    {
      id: "CONTROL_IF",
      selector: "doIf",
      spec: "if %1 then",
      inputs: ["%b"],
      shape: "c-block",
      category: "control"
    },
    {
      id: "CONTROL_WAITUNTIL",
      selector: "doWaitUntil",
      spec: "wait until %1",
      inputs: ["%b"],
      shape: "stack",
      category: "control"
    },
    {
      id: "CONTROL_REPEATUNTIL",
      selector: "doUntil",
      spec: "repeat until %1",
      inputs: ["%b"],
      shape: "c-block",
      category: "control",
      hasLoopArrow: true
    },
    {
      id: "CONTROL_STOP",
      selector: "stopScripts",
      spec: "stop %1",
      inputs: ["%m.stop"],
      shape: "cap",
      category: "control"
    },
    {
      id: "CONTROL_STARTASCLONE",
      selector: "whenCloned",
      spec: "when I start as a clone",
      inputs: [],
      shape: "hat",
      category: "control"
    },
    {
      id: "CONTROL_CREATECLONEOF",
      selector: "createCloneOf",
      spec: "create clone of %1",
      inputs: ["%m.spriteOnly"],
      shape: "stack",
      category: "control"
    },
    {
      id: "CONTROL_DELETETHISCLONE",
      selector: "deleteClone",
      spec: "delete this clone",
      inputs: [],
      shape: "cap",
      category: "control"
    },
    {
      id: "SENSING_ASKANDWAIT",
      selector: "doAsk",
      spec: "ask %1 and wait",
      inputs: ["%s"],
      shape: "stack",
      category: "sensing"
    },
    {
      id: "videoSensing.videoToggle",
      selector: "setVideoState",
      spec: "turn video %1",
      inputs: ["%m.videoState"],
      shape: "stack",
      category: "video"
    },
    {
      id: "videoSensing.setVideoTransparency",
      selector: "setVideoTransparency",
      spec: "set video transparency to %1%",
      inputs: ["%n"],
      shape: "stack",
      category: "video"
    },
    {
      id: "videoSensing.whenMotionGreaterThan",
      spec: "when video motion > %1",
      inputs: ["%n"],
      shape: "hat",
      category: "video"
    },
    {
      id: "SENSING_RESETTIMER",
      selector: "timerReset",
      spec: "reset timer",
      inputs: [],
      shape: "stack",
      category: "sensing"
    },
    {
      id: "DATA_SETVARIABLETO",
      selector: "setVar:to:",
      spec: "set %1 to %2",
      inputs: ["%m.var", "%s"],
      shape: "stack",
      category: "variables"
    },
    {
      id: "DATA_CHANGEVARIABLEBY",
      selector: "changeVar:by:",
      spec: "change %1 by %2",
      inputs: ["%m.var", "%n"],
      shape: "stack",
      category: "variables"
    },
    {
      id: "DATA_SHOWVARIABLE",
      selector: "showVariable:",
      spec: "show variable %1",
      inputs: ["%m.var"],
      shape: "stack",
      category: "variables"
    },
    {
      id: "DATA_HIDEVARIABLE",
      selector: "hideVariable:",
      spec: "hide variable %1",
      inputs: ["%m.var"],
      shape: "stack",
      category: "variables"
    },
    {
      id: "DATA_ADDTOLIST",
      selector: "append:toList:",
      spec: "add %1 to %2",
      inputs: ["%s", "%m.list"],
      shape: "stack",
      category: "list"
    },
    {
      id: "DATA_DELETEOFLIST",
      selector: "deleteLine:ofList:",
      spec: "delete %1 of %2",
      inputs: ["%d.listDeleteItem", "%m.list"],
      shape: "stack",
      category: "list"
    },
    {
      id: "DATA_DELETEALLOFLIST",
      spec: "delete all of %1",
      inputs: ["%m.list"],
      shape: "stack",
      category: "list"
    },
    {
      id: "MOTION_IFONEDGEBOUNCE",
      selector: "bounceOffEdge",
      spec: "if on edge, bounce",
      inputs: [],
      shape: "stack",
      category: "motion"
    },
    {
      id: "DATA_INSERTATLIST",
      selector: "insert:at:ofList:",
      spec: "insert %1 at %2 of %3",
      inputs: ["%s", "%d.listItem", "%m.list"],
      shape: "stack",
      category: "list"
    },
    {
      id: "DATA_REPLACEITEMOFLIST",
      selector: "setLine:ofList:to:",
      spec: "replace item %1 of %2 with %3",
      inputs: ["%d.listItem", "%m.list", "%s"],
      shape: "stack",
      category: "list"
    },
    {
      id: "DATA_SHOWLIST",
      selector: "showList:",
      spec: "show list %1",
      inputs: ["%m.list"],
      shape: "stack",
      category: "list"
    },
    {
      id: "DATA_HIDELIST",
      selector: "hideList:",
      spec: "hide list %1",
      inputs: ["%m.list"],
      shape: "stack",
      category: "list"
    },
    {
      id: "MOTION_XPOSITION",
      selector: "xpos",
      spec: "x position",
      inputs: [],
      shape: "reporter",
      category: "motion"
    },
    {
      id: "MOTION_YPOSITION",
      selector: "ypos",
      spec: "y position",
      inputs: [],
      shape: "reporter",
      category: "motion"
    },
    {
      id: "MOTION_DIRECTION",
      selector: "heading",
      spec: "direction",
      inputs: [],
      shape: "reporter",
      category: "motion"
    },
    {
      id: "SENSING_OF_COSTUMENUMBER",
      selector: "costumeIndex",
      spec: "costume #",
      inputs: [],
      shape: "reporter",
      category: "looks"
    },
    {
      id: "LOOKS_COSTUMENUMBERNAME",
      selector: "LOOKS_COSTUMENUMBERNAME",
      spec: "costume %1",
      inputs: ["%m"],
      shape: "reporter",
      category: "looks"
    },
    {
      id: "LOOKS_SIZE",
      selector: "scale",
      spec: "size",
      inputs: [],
      shape: "reporter",
      category: "looks"
    },
    {
      id: "SENSING_OF_BACKDROPNAME",
      selector: "sceneName",
      spec: "backdrop name",
      inputs: [],
      shape: "reporter",
      category: "looks"
    },
    {
      id: "LOOKS_BACKDROPNUMBERNAME",
      spec: "backdrop %1",
      inputs: ["%m"],
      shape: "reporter",
      category: "looks"
    },
    {
      id: "SENSING_OF_BACKDROPNUMBER",
      selector: "backgroundIndex",
      spec: "backdrop #",
      inputs: [],
      shape: "reporter",
      category: "looks"
    },
    {
      id: "SOUND_VOLUME",
      selector: "volume",
      spec: "volume",
      inputs: [],
      shape: "reporter",
      category: "sound"
    },
    {
      id: "music.getTempo",
      selector: "tempo",
      spec: "tempo",
      inputs: [],
      shape: "reporter",
      category: "music"
    },
    {
      id: "SENSING_TOUCHINGOBJECT",
      selector: "touching:",
      spec: "touching %1?",
      inputs: ["%m.touching"],
      shape: "boolean",
      category: "sensing"
    },
    {
      id: "SENSING_TOUCHINGCOLOR",
      selector: "touchingColor:",
      spec: "touching color %1?",
      inputs: ["%c"],
      shape: "boolean",
      category: "sensing"
    },
    {
      id: "SENSING_COLORISTOUCHINGCOLOR",
      selector: "color:sees:",
      spec: "color %1 is touching %2?",
      inputs: ["%c", "%c"],
      shape: "boolean",
      category: "sensing"
    },
    {
      id: "SENSING_DISTANCETO",
      selector: "distanceTo:",
      spec: "distance to %1",
      inputs: ["%m.spriteOrMouse"],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "SENSING_ANSWER",
      selector: "answer",
      spec: "answer",
      inputs: [],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "SENSING_KEYPRESSED",
      selector: "keyPressed:",
      spec: "key %1 pressed?",
      inputs: ["%m.key"],
      shape: "boolean",
      category: "sensing"
    },
    {
      id: "SENSING_MOUSEDOWN",
      selector: "mousePressed",
      spec: "mouse down?",
      inputs: [],
      shape: "boolean",
      category: "sensing"
    },
    {
      id: "SENSING_MOUSEX",
      selector: "mouseX",
      spec: "mouse x",
      inputs: [],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "SENSING_MOUSEY",
      selector: "mouseY",
      spec: "mouse y",
      inputs: [],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "SENSING_SETDRAGMODE",
      spec: "set drag mode %1",
      inputs: ["%m"],
      shape: "stack",
      category: "sensing"
    },
    {
      id: "SENSING_LOUDNESS",
      selector: "soundLevel",
      spec: "loudness",
      inputs: [],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "videoSensing.videoOn",
      selector: "senseVideoMotion",
      spec: "video %1 on %2",
      inputs: ["%m.videoMotionType", "%m.stageOrThis"],
      shape: "reporter",
      category: "video"
    },
    {
      id: "SENSING_TIMER",
      selector: "timer",
      spec: "timer",
      inputs: [],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "SENSING_OF",
      selector: "getAttribute:of:",
      spec: "%1 of %2",
      inputs: ["%m.attribute", "%m.spriteOrStage"],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "SENSING_CURRENT",
      selector: "timeAndDate",
      spec: "current %1",
      inputs: ["%m.timeAndDate"],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "SENSING_DAYSSINCE2000",
      selector: "timestamp",
      spec: "days since 2000",
      inputs: [],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "SENSING_ONLINE",
      spec: "online?",
      inputs: [],
      shape: "boolean",
      category: "sensing"
    },
    {
      id: "SENSING_USERNAME",
      selector: "getUserName",
      spec: "username",
      inputs: [],
      shape: "reporter",
      category: "sensing"
    },
    {
      id: "OPERATORS_ADD",
      selector: "+",
      spec: "%1 + %2",
      inputs: ["%n", "%n"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_SUBTRACT",
      selector: "-",
      spec: "%1 - %2",
      inputs: ["%n", "%n"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_MULTIPLY",
      selector: "*",
      spec: "%1 * %2",
      inputs: ["%n", "%n"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_DIVIDE",
      selector: "/",
      spec: "%1 / %2",
      inputs: ["%n", "%n"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_RANDOM",
      selector: "randomFrom:to:",
      spec: "pick random %1 to %2",
      inputs: ["%n", "%n"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_LT",
      selector: "<",
      spec: "%1 < %2",
      inputs: ["%s", "%s"],
      shape: "boolean",
      category: "operators"
    },
    {
      id: "OPERATORS_EQUALS",
      selector: "=",
      spec: "%1 = %2",
      inputs: ["%s", "%s"],
      shape: "boolean",
      category: "operators"
    },
    {
      id: "OPERATORS_GT",
      selector: ">",
      spec: "%1 > %2",
      inputs: ["%s", "%s"],
      shape: "boolean",
      category: "operators"
    },
    {
      id: "OPERATORS_AND",
      selector: "&",
      spec: "%1 and %2",
      inputs: ["%b", "%b"],
      shape: "boolean",
      category: "operators"
    },
    {
      id: "OPERATORS_OR",
      selector: "|",
      spec: "%1 or %2",
      inputs: ["%b", "%b"],
      shape: "boolean",
      category: "operators"
    },
    {
      id: "OPERATORS_NOT",
      selector: "not",
      spec: "not %1",
      inputs: ["%b"],
      shape: "boolean",
      category: "operators"
    },
    {
      id: "OPERATORS_JOIN",
      selector: "concatenate:with:",
      spec: "join %1 %2",
      inputs: ["%s", "%s"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_LETTEROF",
      selector: "letter:of:",
      spec: "letter %1 of %2",
      inputs: ["%n", "%s"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_LENGTH",
      selector: "stringLength:",
      spec: "length of %1",
      inputs: ["%s"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_MOD",
      selector: "%",
      spec: "%1 mod %2",
      inputs: ["%n", "%n"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_ROUND",
      selector: "rounded",
      spec: "round %1",
      inputs: ["%n"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_MATHOP",
      selector: "computeFunction:of:",
      spec: "%1 of %2",
      inputs: ["%m.mathOp", "%n"],
      shape: "reporter",
      category: "operators"
    },
    {
      id: "OPERATORS_CONTAINS",
      spec: "%1 contains %2?",
      inputs: ["%s", "%s"],
      shape: "boolean",
      category: "operators"
    },
    {
      id: "DATA_ITEMOFLIST",
      selector: "getLine:ofList:",
      spec: "item %1 of %2",
      inputs: ["%d.listItem", "%m.list"],
      shape: "reporter",
      category: "list"
    },
    {
      id: "DATA_ITEMNUMOFLIST",
      spec: "item # of %1 in %2",
      inputs: ["%s", "%m.list"],
      shape: "reporter",
      category: "list"
    },
    {
      id: "DATA_LENGTHOFLIST",
      selector: "lineCountOfList:",
      spec: "length of %1",
      inputs: ["%m.list"],
      shape: "reporter",
      category: "list"
    },
    {
      id: "DATA_LISTCONTAINSITEM",
      selector: "list:contains:",
      spec: "%1 contains %2?",
      inputs: ["%m.list", "%s"],
      shape: "boolean",
      category: "list"
    },
    {
      id: "CONTROL_ELSE",
      spec: "else",
      inputs: [],
      shape: "celse",
      category: "control"
    },
    {
      id: "scratchblocks:end",
      spec: "end",
      inputs: [],
      shape: "cend",
      category: "control"
    },
    {
      id: "scratchblocks:ellipsis",
      spec: ". . .",
      inputs: [],
      shape: "stack",
      category: "grey"
    },
    {
      id: "scratchblocks:addInput",
      spec: "%1 @addInput",
      inputs: ["%n"],
      shape: "ring",
      category: "grey"
    },
    {
      id: "SENSING_USERID",
      spec: "user id",
      inputs: [],
      shape: "reporter",
      category: "obsolete"
    },
    {
      selector: "doIf",
      spec: "if %1",
      inputs: ["%b"],
      shape: "c-block",
      category: "obsolete"
    },
    {
      selector: "doForeverIf",
      spec: "forever if %1",
      inputs: ["%b"],
      shape: "c-block cap",
      category: "obsolete"
    },
    {
      selector: "doReturn",
      spec: "stop script",
      inputs: [],
      shape: "cap",
      category: "obsolete"
    },
    {
      selector: "stopAll",
      spec: "stop all",
      inputs: [],
      shape: "cap",
      category: "obsolete"
    },
    {
      selector: "lookLike:",
      spec: "switch to costume %1",
      inputs: ["%m.costume"],
      shape: "stack",
      category: "obsolete"
    },
    {
      selector: "nextScene",
      spec: "next background",
      inputs: [],
      shape: "stack",
      category: "obsolete"
    },
    {
      selector: "startScene",
      spec: "switch to background %1",
      inputs: ["%m.backdrop"],
      shape: "stack",
      category: "obsolete"
    },
    {
      selector: "backgroundIndex",
      spec: "background #",
      inputs: [],
      shape: "reporter",
      category: "obsolete"
    },
    {
      id: "SENSING_LOUD",
      selector: "isLoud",
      spec: "loud?",
      inputs: [],
      shape: "boolean",
      category: "obsolete"
    },
    // TODO define
    {
      id: "faceSensing.goToPart",
      spec: "go to %1",
      inputs: ["%m"],
      shape: "stack",
      category: "faceSensing"
    },
    {
      id: "faceSensing.pointInFaceTiltDirection",
      spec: "point in direction of face tilt",
      inputs: [],
      shape: "stack",
      category: "faceSensing"
    },
    {
      id: "faceSensing.setSizeToFaceSize",
      spec: "set size to face size",
      inputs: [],
      shape: "stack",
      category: "faceSensing"
    },
    {
      id: "faceSensing.whenTilted",
      spec: "when face tilts %1",
      inputs: ["%m"],
      shape: "hat",
      category: "faceSensing"
    },
    {
      id: "faceSensing.whenSpriteTouchesPart",
      spec: "when this sprite touches a %1",
      inputs: ["%m"],
      shape: "hat",
      category: "faceSensing"
    },
    {
      id: "faceSensing.whenFaceDetected",
      spec: "when a face is detected",
      inputs: [],
      shape: "hat",
      category: "faceSensing"
    },
    {
      id: "faceSensing.faceDetected",
      spec: "a face is detected?",
      inputs: [],
      shape: "boolean",
      category: "faceSensing"
    },
    {
      id: "faceSensing.faceTilt",
      spec: "face tilt",
      inputs: [],
      shape: "reporter",
      category: "faceSensing"
    },
    {
      id: "faceSensing.faceSize",
      spec: "face size",
      inputs: [],
      shape: "reporter",
      category: "faceSensing"
    },
    {
      id: "text2speech.speakAndWaitBlock",
      spec: "speak %1",
      inputs: ["%s"],
      shape: "stack",
      category: "tts"
    },
    {
      id: "text2speech.setVoiceBlock",
      spec: "set voice to %1",
      inputs: ["%m"],
      shape: "stack",
      category: "tts"
    },
    {
      id: "text2speech.setLanguageBlock",
      spec: "set language to %1",
      inputs: ["%m"],
      shape: "stack",
      category: "tts"
    },
    {
      id: "translate.translateBlock",
      spec: "translate %1 to %2",
      inputs: ["%s", "%m"],
      shape: "reporter",
      category: "translate"
    },
    {
      id: "translate.viewerLanguage",
      spec: "language",
      shape: "reporter",
      category: "translate"
    },
    {
      id: "makeymakey.whenKeyPressed",
      spec: "when %1 key pressed",
      inputs: ["%m"],
      // this is not %m.key
      shape: "hat",
      category: "makeymakey"
    },
    {
      id: "makeymakey.whenKeysPressedInOrder",
      spec: "when %1 pressed in order",
      inputs: ["%m"],
      shape: "hat",
      category: "makeymakey"
    },
    {
      id: "microbit.whenButtonPressed",
      spec: "when %1 button pressed",
      inputs: ["%m"],
      shape: "hat",
      category: "microbit"
    },
    {
      id: "microbit.isButtonPressed",
      spec: "%1 button pressed?",
      inputs: ["%m"],
      shape: "boolean",
      category: "microbit"
    },
    {
      id: "microbit.whenGesture",
      spec: "when %1",
      inputs: ["%m"],
      shape: "hat",
      category: "microbit"
    },
    {
      id: "microbit.displaySymbol",
      spec: "display %1",
      inputs: ["%m"],
      // TODO add matrix support
      shape: "stack",
      category: "microbit"
    },
    {
      id: "microbit.displayText",
      spec: "display text %1",
      inputs: ["%s"],
      shape: "stack",
      category: "microbit"
    },
    {
      id: "microbit.clearDisplay",
      spec: "clear display",
      shape: "stack",
      category: "microbit"
    },
    {
      id: "microbit.whenTilted",
      spec: "when tilted %1",
      inputs: ["%m"],
      shape: "hat",
      category: "microbit"
    },
    {
      id: "microbit.isTilted",
      spec: "tilted %1?",
      inputs: ["%m"],
      shape: "boolean",
      category: "microbit"
    },
    {
      id: "microbit.tiltAngle",
      spec: "tilt angle %1",
      inputs: ["%m"],
      shape: "reporter",
      category: "microbit"
    },
    {
      id: "microbit.whenPinConnected",
      spec: "when pin %1 connected",
      inputs: ["%m"],
      shape: "hat",
      category: "microbit"
    },
    {
      id: "ev3.motorTurnClockwise",
      spec: "motor %1 turn this way for %2 seconds",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "ev3"
    },
    {
      id: "ev3.motorTurnCounterClockwise",
      spec: "motor %1 turn that way for %2 seconds",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "ev3"
    },
    {
      id: "ev3.motorSetPower",
      spec: "motor %1 set power %2%",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "ev3"
    },
    {
      id: "ev3.getMotorPosition",
      spec: "motor %1 position",
      inputs: ["%m"],
      shape: "reporter",
      category: "ev3"
    },
    {
      id: "ev3.whenButtonPressed",
      spec: "when button %1 pressed",
      inputs: ["%m"],
      shape: "hat",
      category: "ev3"
    },
    {
      id: "ev3.whenDistanceLessThan",
      spec: "when distance < %1",
      inputs: ["%n"],
      shape: "hat",
      category: "ev3"
    },
    {
      id: "ev3.whenBrightnessLessThan",
      spec: "when brightness < %1",
      inputs: ["%n"],
      shape: "hat",
      category: "ev3"
    },
    {
      id: "ev3.buttonPressed",
      spec: "button %1 pressed?",
      inputs: ["%m"],
      shape: "boolean",
      category: "ev3"
    },
    {
      id: "ev3.getDistance",
      spec: "distance",
      shape: "reporter",
      category: "ev3"
    },
    {
      id: "ev3.getBrightness",
      spec: "brightness",
      shape: "reporter",
      category: "ev3"
    },
    {
      id: "ev3.beepNote",
      spec: "beep note %1 for %2 secs",
      inputs: ["%d.note", "%n"],
      // we can use %d.note here
      shape: "stack",
      category: "ev3"
    },
    {
      id: "wedo2.motorOn",
      spec: "turn %1 on",
      inputs: ["%m.motor"],
      shape: "stack",
      category: "wedo"
    },
    {
      id: "wedo2.motorOff",
      spec: "turn %1 off",
      inputs: ["%m.motor"],
      shape: "stack",
      category: "wedo"
    },
    {
      id: "wedo2.startMotorPower",
      spec: "set %1 power to %2",
      inputs: ["%m.motor", "%n"],
      shape: "stack",
      category: "wedo"
    },
    {
      id: "wedo2.setMotorDirection",
      spec: "set %1 direction to %2",
      inputs: ["%m.motor2", "%m.motorDirection"],
      shape: "stack",
      category: "wedo"
    },
    {
      id: "wedo2.whenDistance",
      spec: "when distance %1 %2",
      inputs: ["%m.lessMore", "%n"],
      shape: "hat",
      category: "wedo"
    },
    {
      id: "wedo2.getDistance",
      spec: "distance",
      inputs: [],
      shape: "reporter",
      category: "wedo"
    },
    {
      id: "wedo2.motorOnFor",
      spec: "turn %1 on for %2 seconds",
      inputs: ["%m.motor", "%n"],
      shape: "stack",
      category: "wedo"
    },
    {
      id: "wedo2.setLightHue",
      spec: "set light color to %1",
      inputs: ["%n"],
      shape: "stack",
      category: "wedo"
    },
    {
      id: "wedo2.playNoteFor",
      spec: "play note %1 for %2 seconds",
      inputs: ["%n", "%n"],
      shape: "stack",
      category: "wedo"
    },
    {
      id: "wedo2.whenTilted",
      spec: "when tilted %1",
      inputs: ["%m.xxx"],
      shape: "hat",
      category: "wedo"
    },
    {
      id: "wedo2.isTilted",
      spec: "tilted %1?",
      inputs: ["%m"],
      shape: "boolean",
      category: "wedo"
    },
    {
      id: "wedo2.getTiltAngle",
      spec: "tilt angle %1",
      inputs: ["%m.xxx"],
      shape: "reporter",
      category: "wedo"
    },
    {
      id: "gdxfor.whenGesture",
      spec: "when %1",
      inputs: ["%m"],
      shape: "hat",
      category: "gdxfor"
    },
    {
      id: "gdxfor.whenForcePushedOrPulled",
      spec: "when force sensor %1",
      inputs: ["%m"],
      shape: "hat",
      category: "gdxfor"
    },
    {
      id: "gdxfor.getForce",
      spec: "force",
      shape: "reporter",
      category: "gdxfor"
    },
    {
      id: "gdxfor.whenTilted",
      spec: "when tilted %1",
      inputs: ["%m"],
      shape: "hat",
      category: "gdxfor"
    },
    {
      id: "gdxfor.isTilted",
      spec: "tilted %1?",
      inputs: ["%m"],
      shape: "boolean",
      category: "gdxfor"
    },
    {
      id: "gdxfor.getTilt",
      spec: "tilt angle %1",
      inputs: ["%m"],
      shape: "reporter",
      category: "gdxfor"
    },
    {
      id: "gdxfor.isFreeFalling",
      spec: "falling?",
      shape: "boolean",
      category: "gdxfor"
    },
    {
      id: "gdxfor.getSpin",
      spec: "spin speed %1",
      inputs: ["%m"],
      shape: "reporter",
      category: "gdxfor"
    },
    {
      id: "gdxfor.getAcceleration",
      spec: "acceleration %1",
      inputs: ["%m"],
      shape: "reporter",
      category: "gdxfor"
    },
    {
      id: "boost.motorOnFor",
      spec: "turn motor %1 for %2 seconds",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "boost"
    },
    {
      id: "boost.motorOnForRotation",
      spec: "turn motor %1 for %2 rotations",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "boost"
    },
    {
      id: "boost.motorOn",
      spec: "turn motor %1 on",
      inputs: ["%m"],
      shape: "stack",
      category: "boost"
    },
    {
      id: "boost.motorOff",
      spec: "turn motor %1 off",
      inputs: ["%m"],
      shape: "stack",
      category: "boost"
    },
    {
      id: "boost.setMotorPower",
      spec: "set motor %1 speed to %2%",
      inputs: ["%m", "%n"],
      shape: "stack",
      category: "boost"
    },
    {
      id: "boost.setMotorDirection",
      spec: "set motor %1 direction %2",
      inputs: ["%m", "%m"],
      shape: "stack",
      category: "boost"
    },
    {
      id: "boost.getMotorPosition",
      spec: "motor %1 position",
      inputs: ["%m"],
      shape: "reporter",
      category: "boost"
    },
    {
      id: "boost.whenColor",
      spec: "when %1 brick seen",
      inputs: ["%m"],
      shape: "hat",
      category: "boost"
    },
    {
      id: "boost.seeingColor",
      spec: "seeing %1 brick?",
      inputs: ["%m"],
      shape: "boolean",
      category: "boost"
    },
    {
      id: "boost.whenTilted",
      spec: "when tilted %1",
      inputs: ["%m"],
      shape: "hat",
      category: "boost"
    },
    {
      id: "boost.getTiltAngle",
      spec: "tilt angle %1",
      inputs: ["%m"],
      shape: "reporter",
      category: "boost"
    },
    {
      id: "boost.setLightHue",
      spec: "set light color to %1",
      inputs: ["%n"],
      shape: "stack",
      category: "boost"
    }
  ];

  // scripts/blockcoding/js-reference/vendor/scratchblocks/blocks.js
  var overrideCategories = [
    "motion",
    "looks",
    "sound",
    "variables",
    "list",
    "events",
    "control",
    "sensing",
    "operators",
    "custom",
    "custom-arg",
    "extension",
    "grey",
    "obsolete",
    ...Object.keys(extensions),
    ...Object.keys(aliasExtensions)
  ];
  var aliasCategories = Object.assign(/* @__PURE__ */ Object.create(null), {
    gray: "grey",
    other: "grey"
  });
  var overrideShapes = [
    "hat",
    "cap",
    "stack",
    "boolean",
    "reporter",
    "ring",
    "cat"
  ];
  var rtlLanguages = ["ar", "ckb", "fa", "he"];
  var inputNumberPat = /%([0-9]+)/;
  var inputPat = /(%[a-zA-Z0-9](?:\.[a-zA-Z0-9]+)?)/;
  var inputPatGlobal = new RegExp(inputPat.source, "g");
  var iconPat = /(@[a-zA-Z]+)/;
  var splitPat = new RegExp(`${inputPat.source}|${iconPat.source}| +`, "g");
  var hexColorPat = /^#(?:[0-9a-fA-F]{3}){1,2}?$/;
  function parseInputNumber(part) {
    const m = inputNumberPat.exec(part);
    return m ? +m[1] : 0;
  }
  function parseSpec(spec) {
    const parts = spec.split(splitPat).filter((x) => x);
    const inputs = parts.filter((p) => inputPat.test(p));
    return {
      spec,
      parts,
      inputs,
      hash: hashSpec(spec)
    };
  }
  function hashSpec(spec) {
    return minifyHash(spec.replace(inputPatGlobal, " _ "));
  }
  function minifyHash(hash) {
    return hash.replace(/_/g, " _ ").replace(/ +/g, " ").replace(/[,%?:]/g, "").replace(/ß/g, "ss").replace(/ä/g, "a").replace(/ö/g, "o").replace(/ü/g, "u").replace(". . .", "...").replace(/^…$/, "...").trim().toLowerCase();
  }
  var blocksById = {};
  var allBlocks = commands_default.map((def) => {
    if (!def.id) {
      if (!def.selector) {
        throw new Error(`Missing ID: ${def.spec}`);
      }
      def.id = `sb2:${def.selector}`;
    }
    if (!def.spec) {
      throw new Error(`Missing spec: ${def.id}`);
    }
    const info = {
      id: def.id,
      // Used for Scratch 3 translations
      spec: def.spec,
      // Used for Scratch 2 translations
      parts: def.spec.split(splitPat).filter((x) => x),
      selector: def.selector || `sb3:${def.id}`,
      // Used for JSON marshalling
      inputs: def.inputs == null ? [] : def.inputs,
      shape: def.shape,
      category: def.category,
      hasLoopArrow: !!def.hasLoopArrow
    };
    if (blocksById[info.id]) {
      throw new Error(`Duplicate ID: ${info.id}`);
    }
    blocksById[info.id] = info;
    return info;
  });
  var unicodeIcons = {
    "@greenFlag": "\u2691",
    "@turnRight": "\u21BB",
    "@turnLeft": "\u21BA",
    "@addInput": "\u25B8",
    "@delInput": "\u25C2"
  };
  var allLanguages = {};
  function loadLanguage(code, language) {
    const blocksByHash = language.blocksByHash = {};
    Object.keys(language.commands).forEach((blockId) => {
      const nativeSpec = language.commands[blockId];
      const block = blocksById[blockId];
      const nativeHash = hashSpec(nativeSpec);
      if (!blocksByHash[nativeHash]) {
        blocksByHash[nativeHash] = [];
      }
      blocksByHash[nativeHash].push(block);
      const m = iconPat.exec(block.spec);
      if (m) {
        const image = m[0];
        const hash = nativeHash.replace(hashSpec(image), unicodeIcons[image]);
        if (!blocksByHash[hash]) {
          blocksByHash[hash] = [];
        }
        blocksByHash[hash].push(block);
      }
    });
    language.nativeAliases = {};
    Object.keys(language.aliases).forEach((alias) => {
      const blockId = language.aliases[alias];
      const block = blocksById[blockId];
      if (block === void 0) {
        throw new Error(`Invalid alias '${blockId}'`);
      }
      const aliasHash = hashSpec(alias);
      if (!blocksByHash[aliasHash]) {
        blocksByHash[aliasHash] = [];
      }
      blocksByHash[aliasHash].push(block);
      if (!language.nativeAliases[blockId]) {
        language.nativeAliases[blockId] = [];
      }
      language.nativeAliases[blockId].push(alias);
    });
    Object.keys(language.renamedBlocks || {}).forEach((alt) => {
      const id = language.renamedBlocks[alt];
      if (!blocksById[id]) {
        throw new Error(`Unknown ID: ${id}`);
      }
      const block = blocksById[id];
      const hash = hashSpec(alt);
      if (!english.blocksByHash[hash]) {
        english.blocksByHash[hash] = [];
      }
      english.blocksByHash[hash].push(block);
    });
    language.nativeDropdowns = {};
    Object.keys(language.dropdowns).forEach((name) => {
      const nativeName = language.dropdowns[name];
      language.nativeDropdowns[nativeName] = name;
    });
    language.code = code;
    allLanguages[code] = language;
  }
  function loadLanguages(languages) {
    Object.keys(languages).forEach((code) => loadLanguage(code, languages[code]));
  }
  var english = {
    aliases: {
      "turn ccw %1 degrees": "MOTION_TURNLEFT",
      "turn left %1 degrees": "MOTION_TURNLEFT",
      "turn cw %1 degrees": "MOTION_TURNRIGHT",
      "turn right %1 degrees": "MOTION_TURNRIGHT",
      "when flag clicked": "EVENT_WHENFLAGCLICKED",
      "when gf clicked": "EVENT_WHENFLAGCLICKED",
      "when green flag clicked": "EVENT_WHENFLAGCLICKED"
    },
    renamedBlocks: {
      "say %1 for %2 secs": "LOOKS_SAYFORSECS",
      "think %1 for %2 secs": "LOOKS_THINKFORSECS",
      "play sound %1": "SOUND_PLAY",
      "wait %1 secs": "CONTROL_WAIT",
      clear: "pen.clear"
    },
    definePrefix: ["define"],
    defineSuffix: [],
    // For ignoring the lt sign in the "when distance < _" block
    ignorelt: ["when distance"],
    // Valid arguments to "of" dropdown, for resolving ambiguous situations
    math: [
      "abs",
      "floor",
      "ceiling",
      "sqrt",
      "sin",
      "cos",
      "tan",
      "asin",
      "acos",
      "atan",
      "ln",
      "log",
      "e ^",
      "10 ^"
    ],
    // Language name is needed for the English locale as well
    name: "English",
    // Valid arguments to "go to" face sensing dropdown, for resolving ambiguous situations
    faceParts: [
      "nose",
      "mouth",
      "left eye",
      "right eye",
      "between eyes",
      "left ear",
      "right ear",
      "top of head"
    ],
    // Valid arguments to "sound effect" dropdown, for resolving ambiguous situations
    soundEffects: ["pitch", "pan left/right"],
    // Valid arguments to "microbit when" dropdown
    microbitWhen: ["moved", "shaken", "jumped"],
    // For detecting the "stop" cap / stack block
    osis: ["other scripts in sprite", "other scripts in stage"],
    dropdowns: {},
    commands: {}
  };
  allBlocks.forEach((info) => {
    english.commands[info.id] = info.spec;
  });
  loadLanguages({
    en: english
  });
  function registerCheck(id, func) {
    if (!blocksById[id]) {
      throw new Error(`Unknown ID: ${id}`);
    }
    blocksById[id].accepts = func;
  }
  function specialCase(id, func) {
    if (!blocksById[id]) {
      throw new Error(`Unknown ID: ${id}`);
    }
    blocksById[id].specialCase = func;
  }
  function disambig(id1, id2, test) {
    registerCheck(id1, (_, children, lang) => {
      return test(children, lang);
    });
    registerCheck(id2, (_, children, lang) => {
      return !test(children, lang);
    });
  }
  disambig("OPERATORS_MATHOP", "SENSING_OF", (children, lang) => {
    const first = children[0];
    if (!first.isInput) {
      return;
    }
    const name = first.value;
    return lang.math.includes(name);
  });
  disambig("SOUND_CHANGEEFFECTBY", "LOOKS_CHANGEEFFECTBY", (children, lang) => {
    for (const child of children) {
      if (child.shape === "dropdown") {
        const name = child.value;
        for (const effect of lang.soundEffects) {
          if (minifyHash(effect) === minifyHash(name)) {
            return true;
          }
        }
      }
    }
    return false;
  });
  disambig("SOUND_SETEFFECTO", "LOOKS_SETEFFECTTO", (children, lang) => {
    for (const child of children) {
      if (child.shape === "dropdown") {
        const name = child.value;
        for (const effect of lang.soundEffects) {
          if (minifyHash(effect) === minifyHash(name)) {
            return true;
          }
        }
      }
    }
    return false;
  });
  disambig("DATA_LENGTHOFLIST", "OPERATORS_LENGTH", (children, _lang) => {
    const last = children[children.length - 1];
    if (!last.isInput) {
      return;
    }
    return last.shape === "dropdown";
  });
  disambig("DATA_LISTCONTAINSITEM", "OPERATORS_CONTAINS", (children, _lang) => {
    const first = children[0];
    if (!first.isInput) {
      return;
    }
    return first.shape === "dropdown";
  });
  disambig("pen.setColor", "pen.setHue", (children, _lang) => {
    const last = children[children.length - 1];
    return last.isInput && last.isColor || last.isBlock;
  });
  disambig("faceSensing.goToPart", "MOTION_GOTO", (children, lang) => {
    for (const child of children) {
      if (child.shape === "dropdown") {
        const name = child.value;
        for (const effect of lang.faceParts) {
          if (minifyHash(effect) === minifyHash(name)) {
            return true;
          }
        }
      }
    }
    return false;
  });
  disambig("microbit.whenGesture", "gdxfor.whenGesture", (children, lang) => {
    for (const child of children) {
      if (child.shape === "dropdown") {
        const name = child.value;
        for (const effect of lang.microbitWhen) {
          if (minifyHash(effect) === minifyHash(name)) {
            return true;
          }
        }
      }
    }
    return false;
  });
  disambig("ev3.buttonPressed", "microbit.isButtonPressed", (children, _lang) => {
    for (const child of children) {
      if (child.shape === "dropdown") {
        switch (minifyHash(child.value)) {
          case "1":
          case "2":
          case "3":
          case "4":
            return true;
          default:
        }
      }
    }
    return false;
  });
  specialCase("CONTROL_STOP", (_, children, lang) => {
    const last = children[children.length - 1];
    if (!last.isInput) {
      return;
    }
    const value = last.value;
    if (lang.osis.includes(value)) {
      return { ...blocksById.CONTROL_STOP, shape: "stack" };
    }
  });
  function lookupHash(hash, info, children, languages) {
    for (const lang of languages) {
      if (Object.prototype.hasOwnProperty.call(lang.blocksByHash, hash)) {
        const collisions = lang.blocksByHash[hash];
        for (let block of collisions) {
          if (info.shape === "reporter" && block.shape !== "reporter" && block.shape !== "ring") {
            continue;
          }
          if (info.shape === "boolean" && block.shape !== "boolean") {
            continue;
          }
          if (collisions.length > 1) {
            if (block.accepts && !block.accepts(info, children, lang)) {
              continue;
            }
          }
          if (block.specialCase) {
            block = block.specialCase(info, children, lang) || block;
          }
          return { type: block, lang };
        }
      }
    }
  }
  function lookupDropdown(name, languages) {
    for (const lang of languages) {
      if (Object.prototype.hasOwnProperty.call(lang.nativeDropdowns, name)) {
        return lang.nativeDropdowns[name];
      }
    }
  }
  function applyOverrides(info, overrides) {
    for (const name of overrides) {
      if (hexColorPat.test(name)) {
        info.color = name;
        info.category = "";
        info.categoryIsDefault = false;
      } else if (aliasCategories[name] && overrideCategories.includes(aliasCategories[name])) {
        info.category = aliasCategories[name];
        info.categoryIsDefault = false;
      } else if (overrideCategories.includes(name)) {
        info.category = name;
        info.categoryIsDefault = false;
      } else if (name === "cat" && info.shape === "define-hat") {
        info.shape = "define-cat";
        info.shapeIsDefault = false;
      } else if (overrideShapes.includes(name)) {
        info.shape = name;
        info.shapeIsDefault = false;
      } else if (name === "loop") {
        info.hasLoopArrow = true;
      } else if (name === "+" || name === "-") {
        info.diff = name;
      } else if (name === "reset") {
        info.categoryIsDefault = false;
        info.isReset = true;
      }
    }
  }
  function blockName(block) {
    const words = [];
    for (const child of block.children) {
      if (!child.isLabel) {
        return;
      }
      words.push(child.value);
    }
    return words.join(" ");
  }

  // scripts/blockcoding/js-reference/vendor/scratchblocks/model.js
  function assert(bool, message) {
    if (!bool) {
      throw new Error(`Assertion failed! ${message || ""}`);
    }
  }
  function indent(text) {
    return text.split("\n").map((line) => {
      return `  ${line}`;
    }).join("\n");
  }
  var DIFF_MARK = "\uFFFCDIFF";
  function prettyPrintDiff(text) {
    if (!text.includes(DIFF_MARK)) {
      return text;
    }
    return text.split("\n").map((line) => {
      if (line.includes(DIFF_MARK)) {
        return line.replace(new RegExp(`( *)${DIFF_MARK}([-+])`, "g"), `$2$1`);
      }
      return `  ${line}`;
    }).join("\n");
  }
  var Label = class {
    constructor(value, cls) {
      this.value = value;
      this.cls = cls || "";
      this.el = null;
      this.height = 12;
      this.metrics = null;
      this.x = 0;
    }
    get isLabel() {
      return true;
    }
    stringify() {
      return this._stringify();
    }
    _stringify() {
      if (this.value === "<" || this.value === ">") {
        return this.value;
      }
      return this.value.replace(/([<>[\](){}\\])/g, "\\$1").replace(/:{2,}/g, (m) => ":" + "\\:".repeat(m.length - 1));
    }
  };
  var Icon = class _Icon {
    constructor(name) {
      this.name = name;
      this.isArrow = name === "loopArrow";
      assert(_Icon.icons[name], `no info for icon ${name}`);
    }
    get isIcon() {
      return true;
    }
    static get icons() {
      return {
        greenFlag: true,
        stopSign: true,
        turnLeft: true,
        turnRight: true,
        loopArrow: true,
        addInput: true,
        delInput: true,
        list: true
      };
    }
    stringify() {
      return this._stringify();
    }
    _stringify() {
      return unicodeIcons[`@${this.name}`] || "";
    }
  };
  var Input = class {
    constructor(shape, value, menu) {
      this.shape = shape;
      this.value = value;
      this.menu = menu || null;
      this.isRound = shape === "number" || shape === "number-dropdown";
      this.isBoolean = shape === "boolean";
      this.isStack = shape === "stack";
      this.isInset = shape === "boolean" || shape === "stack" || shape === "reporter";
      this.isColor = shape === "color";
      this.hasArrow = shape === "dropdown" || shape === "number-dropdown";
      this.isDarker = shape === "boolean" || shape === "stack" || shape === "dropdown";
      this.isSquare = shape === "string" || shape === "color" || shape === "dropdown";
      this.hasLabel = !(this.isColor || this.isInset);
      this.label = this.hasLabel ? new Label(value, `literal-${this.shape}`) : null;
      this.x = 0;
    }
    get isInput() {
      return true;
    }
    stringify() {
      return this._stringify();
    }
    _stringify() {
      if (this.isColor) {
        assert(this.value[0] === "#");
        return `[${this.value}]`;
      }
      let text = this.value ? String(this.value) : "";
      if (this.isSquare) {
        text = text.replace(/([\]\\])/g, "\\$1");
      }
      if (this.isRound) {
        text = text.replace(/([()[<])/g, "\\$1");
      }
      text = text.replace(/ v$/, " \\v");
      if (this.hasArrow) {
        text += " v";
      } else if (hexColorPat.test(text)) {
        text = "\\" + text;
      }
      return this.isRound ? `(${text})` : this.isSquare ? `[${text}]` : this.isBoolean ? "<>" : this.isStack ? "{}" : text;
    }
    translate(_lang) {
      if (this.hasArrow) {
        const value = this.menu || this.value;
        this.value = value;
        this.label = new Label(this.value, `literal-${this.shape}`);
      }
    }
  };
  var Block = class {
    constructor(info, children, comment) {
      assert(info);
      this.info = { ...info };
      this.children = children;
      this.comment = comment || null;
      this.diff = null;
      const shape = this.info.shape;
      this.isHat = shape === "hat" || shape === "cat" || shape === "define-hat" || shape === "define-cat";
      this.hasPuzzle = shape === "stack" || shape === "hat" || shape === "cat" || shape === "c-block" || shape === "define-hat" || shape === "define-cat";
      this.isFinal = /cap/.test(shape);
      this.isCommand = shape === "stack" || shape === "cap" || /block/.test(shape);
      this.isOutline = shape === "outline";
      this.isReporter = shape === "reporter";
      this.isBoolean = shape === "boolean";
      this.isRing = shape === "ring";
      this.hasScript = /block/.test(shape);
      this.isElse = shape === "celse";
      this.isEnd = shape === "cend";
    }
    get isBlock() {
      return true;
    }
    stringify(extras) {
      return prettyPrintDiff(this._stringify(extras));
    }
    _stringify(extras) {
      let firstInput = null;
      let checkAlias = false;
      let text = this.children.map((child, i, arr) => {
        if (child.isIcon) {
          checkAlias = true;
        }
        if (!firstInput && !(child.isLabel || child.isIcon)) {
          firstInput = child;
        }
        if (child.isScript) {
          return child.isEmpty ? "\n" : `
${indent(child._stringify())}
`;
        }
        let next = arr[i + 1];
        next = next && next.name === "loopArrow" ? arr[i + 2] : next;
        const needsSpace = !(next && next.isScript);
        return child._stringify().trim() + (needsSpace ? " " : "");
      }).join("").trim();
      if (this.info.shape === "reporter" && hexColorPat.test(text)) {
        return `(\\${text})`;
      }
      const lang = this.info.language;
      if (checkAlias && lang && this.info.selector) {
        const aliases = lang.nativeAliases[this.info.id];
        if (aliases && aliases.length) {
          let alias = aliases[0];
          if (inputPat.test(alias) && firstInput) {
            alias = alias.replace(inputPat, firstInput._stringify());
          }
          return alias;
        }
      }
      let overrides = extras || this.info.diff || "";
      if (this.info.categoryIsDefault === false || this.info.category === "custom-arg" && (this.isReporter || this.isBoolean) || this.info.category === "custom" && this.info.shape === "stack") {
        if (overrides) {
          overrides += " ";
        }
        if (this.info.isReset && this.info.category === "obsolete") {
          overrides += "reset";
        } else {
          overrides += this.info.category;
        }
      }
      if (this.info.shapeIsDefault === false) {
        if (overrides) {
          overrides += " ";
        }
        overrides += this.info.shape === "define-cat" ? "cat" : this.info.shape;
      }
      if (overrides) {
        text += ` :: ${overrides}`;
      }
      if ((text.startsWith("+") || text.startsWith("-")) && this.info.shape !== "reporter" && this.info.shape !== "boolean") {
        text = `\\${text}`;
      }
      const diff_text = this.diff && !this.info.diff ? `${DIFF_MARK}${this.diff} ` : "";
      return this.hasScript ? diff_text + text + "\n" + (Object.keys(lang.aliases).find(
        (key) => lang.aliases[key] === "scratchblocks:end"
      ) || "end") : this.info.shape === "reporter" ? `${diff_text}(${text})` : this.info.shape === "boolean" ? `${diff_text}<${text}>` : diff_text + text;
    }
    translate(lang, isShallow) {
      if (!lang) {
        throw new Error("Missing language");
      }
      const id = this.info.id;
      if (!id) {
        return;
      }
      if (id === "PROCEDURES_DEFINITION") {
        const outline = this.children.find((child) => child.isOutline);
        this.children = [];
        for (const word of lang.definePrefix) {
          this.children.push(new Label(word));
        }
        this.children.push(outline);
        for (const word of lang.defineSuffix) {
          this.children.push(new Label(word));
        }
        return;
      } else if (id === "PROCEDURES_CALL") {
        this.children.forEach((child) => {
          if (!child.isLabel && !child.isIcon) {
            child.translate(lang);
          }
        });
        return;
      }
      if (this.info.category === "custom-arg" || this.info.category === "custom") {
        return;
      }
      const oldSpec = this.info.language.commands[id];
      const nativeSpec = lang.commands[id];
      if (!nativeSpec) {
        return;
      }
      const nativeInfo = parseSpec(nativeSpec);
      const rawArgs = this.children.filter(
        (child) => !child.isLabel && !child.isIcon
      );
      if (!isShallow) {
        rawArgs.forEach((child) => child.translate(lang));
      }
      const oldParts = parseSpec(oldSpec).parts;
      const oldInputOrder = oldParts.map((part) => parseInputNumber(part)).filter((x) => x);
      let highestNumber = 0;
      const args = oldInputOrder.map((number) => {
        highestNumber = Math.max(highestNumber, number);
        return rawArgs[number - 1];
      });
      const remainingArgs = rawArgs.slice(highestNumber);
      this.children = nativeInfo.parts.map((part) => {
        part = part.trim();
        if (!part) {
          return;
        }
        const number = parseInputNumber(part);
        if (number) {
          return args[number - 1];
        }
        return iconPat.test(part) ? new Icon(part.slice(1)) : new Label(part);
      }).filter((x) => x);
      remainingArgs.forEach((arg, index) => {
        if (index === 1 && this.info.id === "CONTROL_IF") {
          this.children.push(new Label(lang.commands.CONTROL_ELSE));
        }
        this.children.push(arg);
      });
      this.info.language = lang;
      this.info.isRTL = rtlLanguages.includes(lang.code);
      this.info.categoryIsDefault = true;
    }
  };
  var Comment = class {
    constructor(value, hasBlock) {
      this.label = new Label(value, "comment-label");
      this.width = null;
      this.hasBlock = hasBlock;
    }
    get isComment() {
      return true;
    }
    stringify() {
      return this._stringify();
    }
    _stringify() {
      return `// ${this.label.value.trim()}`;
    }
  };
  var Glow = class {
    constructor(child) {
      assert(child);
      this.child = child;
      if (child.isBlock) {
        this.shape = child.info.shape;
        this.info = child.info;
      } else {
        this.shape = "stack";
      }
    }
    get isGlow() {
      return true;
    }
    stringify() {
      return prettyPrintDiff(this._stringify());
    }
    _stringify() {
      if (this.child.isBlock) {
        return this.child._stringify("+");
      }
      const lines = this.child._stringify().split("\n");
      return lines.map((line) => line.includes(DIFF_MARK) ? line : `${DIFF_MARK}+ ${line}`).join("\n");
    }
    translate(lang) {
      this.child.translate(lang);
    }
  };
  var Script = class {
    constructor(blocks) {
      this.blocks = blocks;
      this.isEmpty = !blocks.length;
      this.isFinal = !this.isEmpty && blocks[blocks.length - 1].isFinal;
    }
    get isScript() {
      return true;
    }
    stringify() {
      return prettyPrintDiff(this._stringify());
    }
    _stringify() {
      return this.blocks.map((block) => {
        let line = block._stringify();
        if (block.comment) {
          if (block.isBlock && block.hasScript) {
            const commentText = ` ${block.comment._stringify()}`;
            const nl = line.indexOf("\n");
            if (nl !== -1) {
              line = line.slice(0, nl) + commentText + line.slice(nl);
            } else {
              line += commentText;
            }
          } else {
            line += ` ${block.comment._stringify()}`;
          }
        }
        return line;
      }).join("\n");
    }
    translate(lang) {
      this.blocks.forEach((block) => block.translate && block.translate(lang));
    }
  };
  var Document = class {
    constructor(scripts) {
      this.scripts = scripts;
    }
    stringify() {
      return this.scripts.map((script) => script.stringify()).join("\n\n");
    }
    translate(lang) {
      this.scripts.forEach((script) => script.translate(lang));
    }
  };

  // scripts/blockcoding/js-reference/vendor/scratchblocks/syntax.js
  function assert2(bool, message) {
    if (!bool) {
      throw new Error(`Assertion failed! ${message || ""}`);
    }
  }
  function paintBlock(info, children, languages) {
    let overrides = [];
    if (Array.isArray(children[children.length - 1])) {
      overrides = children.pop();
    }
    const words = [];
    for (const child of children) {
      if (child.isLabel) {
        words.push(child.value);
      } else if (child.isIcon) {
        words.push(`@${child.name}`);
      } else {
        words.push("_");
      }
    }
    const string = words.join(" ");
    const shortHash = info.hash = minifyHash(string);
    let lang;
    let type;
    if (!overrides.includes("reset")) {
      const o = lookupHash(shortHash, info, children, languages);
      if (o) {
        lang = o.lang;
        type = o.type;
        info.language = lang;
        info.isRTL = rtlLanguages.includes(lang.code);
        if (type.shape === "ring" ? info.shape === "reporter" : info.shape === "stack") {
          info.shape = type.shape;
        }
        if (aliasCategories[type.category]) {
          info.category = aliasCategories[type.category];
        } else {
          info.category = type.category;
        }
        info.categoryIsDefault = true;
        if (type.selector) {
          info.selector = type.selector;
        }
        if (type.id) {
          info.id = type.id;
        }
        info.hasLoopArrow = type.hasLoopArrow;
        if (type.spec === ". . .") {
          children = [new Label(". . .")];
        }
      } else {
        for (const lang2 of languages) {
          if (!isDefineBlock(children, lang2)) {
            continue;
          }
          info.shape = "define-hat";
          info.category = "custom";
          const outlineChildren = children.splice(
            lang2.definePrefix.length,
            children.length - lang2.defineSuffix.length
          ).map((child) => {
            if (child.isInput && child.isBoolean) {
              child = paintBlock(
                {
                  shape: "boolean",
                  argument: "boolean",
                  category: "custom-arg"
                },
                [new Label("")],
                languages
              );
            } else if (child.isInput && (child.shape === "string" || child.shape === "number")) {
              const labels = child.value.split(/ +/g).map((word) => new Label(word));
              child = paintBlock(
                {
                  shape: "reporter",
                  argument: child.shape === "string" ? "string" : "number",
                  category: "custom-arg"
                },
                labels,
                languages
              );
            } else if (child.isReporter || child.isBoolean) {
              if (child.info.categoryIsDefault) {
                child.info.category = "custom-arg";
              }
              child.info.argument = child.isBoolean ? "boolean" : "number";
            }
            return child;
          });
          const outlineInfo = {
            shape: "outline",
            category: "custom",
            categoryIsDefault: true,
            hasLoopArrow: false
          };
          const outline = new Block(outlineInfo, outlineChildren);
          children.splice(lang2.definePrefix.length, 0, outline);
          break;
        }
      }
    }
    applyOverrides(info, overrides);
    if (info.hasLoopArrow) {
      children.push(new Icon("loopArrow"));
    }
    const block = new Block(info, children);
    if (type && iconPat.test(type.spec)) {
      block.translate(lang, true);
    }
    if (info.diff === "+") {
      return new Glow(block);
    }
    block.diff = info.diff;
    return block;
  }
  function isDefineBlock(children, lang) {
    if (children.length < lang.definePrefix.length) {
      return false;
    }
    if (children.length < lang.defineSuffix.length) {
      return false;
    }
    for (let i = 0; i < lang.definePrefix.length; i++) {
      const defineWord = lang.definePrefix[i];
      const child = children[i];
      if (!child.isLabel || minifyHash(child.value) !== minifyHash(defineWord)) {
        return false;
      }
    }
    for (let i = 1; i <= lang.defineSuffix.length; i++) {
      const defineWord = lang.defineSuffix[lang.defineSuffix.length - i];
      const child = children[children.length - i];
      if (!child.isLabel || minifyHash(child.value) !== minifyHash(defineWord)) {
        return false;
      }
    }
    return true;
  }
  function parseLines(code, languages) {
    let tok = code[0];
    let index = 0;
    let lineNo = 1;
    function next() {
      if (tok === "\n") {
        lineNo++;
      }
      tok = code[++index];
    }
    function peek() {
      return code[index + 1];
    }
    function peekNonWs() {
      for (let i = index + 1; i < code.length; i++) {
        if (code[i] !== " ") {
          return code[i];
        }
      }
    }
    let sawNL;
    let define = [];
    languages.map((lang) => {
      define = define.concat(lang.define);
    });
    function makeBlock(shape, children) {
      const hasInputs = children.filter((x) => !x.isLabel).length;
      const info = {
        shape,
        category: shape === "reporter" && !hasInputs ? "variables" : "obsolete",
        categoryIsDefault: true,
        hasLoopArrow: false
      };
      return paintBlock(info, children, languages);
    }
    function makeMenu(shape, value) {
      const menu = lookupDropdown(value, languages) || value;
      return new Input(shape, value, menu);
    }
    function pParts(end) {
      const children = [];
      let label;
      while (tok && tok !== "\n") {
        if ((tok === "<" || tok === ">") && end === ">" && // We're parsing a predicate.
        children.length === 1 && // There's exactly one AST node behind us.
        !children[children.length - 1].isLabel) {
          const c = peekNonWs();
          if (c === "[" || c === "(" || c === "<" || c === "{") {
            label = null;
            children.push(new Label(tok));
            next();
            continue;
          }
        }
        if (tok === end) {
          break;
        }
        if (tok === "/" && peek() === "/" && !end) {
          break;
        }
        switch (tok) {
          case "[":
            label = null;
            children.push(pString());
            break;
          case "(":
            label = null;
            children.push(pReporter());
            break;
          case "<":
            label = null;
            children.push(pPredicate());
            break;
          case "{":
            label = null;
            children.push(pEmbedded());
            break;
          case " ":
          case "	":
            next();
            label = null;
            break;
          case "\u25C2":
          case "\u25B8":
            children.push(pIcon());
            label = null;
            break;
          case "@": {
            next();
            let name = "";
            while (tok && /[a-zA-Z]/.test(tok)) {
              name += tok;
              next();
            }
            if (name === "cloud") {
              children.push(new Label("\u2601"));
            } else {
              children.push(
                Object.prototype.hasOwnProperty.call(Icon.icons, name) ? new Icon(name) : new Label(`@${name}`)
              );
            }
            label = null;
            break;
          }
          case "\\":
            next();
          // escape character
          // fallthrough
          case ":":
            if (tok === ":" && peek() === ":") {
              children.push(pOverrides(end));
              return children;
            }
          // fallthrough
          default:
            if (!label) {
              children.push(label = new Label(""));
            }
            label.value += tok;
            next();
        }
      }
      return children;
    }
    function pString() {
      next();
      let s = "";
      let escapeV = false;
      const escaped = tok === "\\";
      while (tok && tok !== "]" && tok !== "\n") {
        if (tok === "\\") {
          next();
          if (tok === "v") {
            escapeV = true;
          }
          if (!tok) {
            break;
          }
        } else {
          escapeV = false;
        }
        s += tok;
        next();
      }
      if (tok === "]") {
        next();
      }
      if (!escaped && hexColorPat.test(s)) {
        return new Input("color", s);
      }
      return !escapeV && / v$/.test(s) ? makeMenu("dropdown", s.slice(0, s.length - 2)) : new Input("string", s);
    }
    function pBlock(end) {
      const children = pParts(end);
      if (tok && tok === "\n") {
        sawNL = true;
        next();
      }
      if (children.length === 0) {
        return;
      }
      if (children.length === 1) {
        const child = children[0];
        if (child.isBlock && (child.isReporter || child.isBoolean || child.isRing)) {
          return child;
        }
      }
      return makeBlock("stack", children);
    }
    function pReporter() {
      next();
      const escaped = tok === "\\";
      if (tok === " ") {
        next();
        if (tok === "v" && peek() === ")") {
          next();
          next();
          return new Input("number-dropdown", "");
        }
      }
      const children = pParts(")");
      if (tok && tok === ")") {
        next();
      }
      if (children.length === 0) {
        return new Input("number", "");
      }
      if (children.length === 1 && children[0].isLabel) {
        const value = children[0].value;
        if (/^[0-9e.-]*$/.test(value)) {
          return new Input("number", value);
        }
        if (!escaped && hexColorPat.test(value)) {
          return new Input("color", value);
        }
      }
      if (children.length > 1 && children.every((child) => child.isLabel)) {
        const last = children[children.length - 1];
        if (last.value === "v") {
          children.pop();
          const value = children.map((l) => l.value).join(" ");
          return makeMenu("number-dropdown", value);
        }
      }
      const block = makeBlock("reporter", children);
      if (block.info && block.info.shape === "ring") {
        const first = block.children[0];
        if (first && first.isInput && first.shape === "number" && first.value === "") {
          block.children[0] = new Input("reporter");
        } else if (first && first.isScript && first.isEmpty || first && first.isBlock && !first.children.length) {
          block.children[0] = new Input("stack");
        }
      }
      return block;
    }
    function pPredicate() {
      next();
      const children = pParts(">");
      if (tok && tok === ">") {
        next();
      }
      if (children.length === 0) {
        return new Input("boolean");
      }
      return makeBlock("boolean", children);
    }
    function pEmbedded() {
      next();
      sawNL = false;
      const f = function() {
        while (tok && tok !== "}") {
          const block = pBlock("}");
          if (block) {
            return block;
          }
        }
      };
      const scripts = parseScripts(f);
      let blocks = [];
      scripts.forEach((script) => {
        blocks = blocks.concat(script.blocks);
      });
      if (tok === "}") {
        next();
      }
      if (!sawNL) {
        assert2(blocks.length <= 1);
        return blocks.length ? blocks[0] : makeBlock("stack", []);
      }
      return new Script(blocks);
    }
    function pIcon() {
      const c = tok;
      next();
      switch (c) {
        case "\u25B8":
          return new Icon("addInput");
        case "\u25C2":
          return new Icon("delInput");
        default:
          return;
      }
    }
    function pOverrides(end) {
      next();
      next();
      const overrides = [];
      let override = "";
      while (tok && tok !== "\n" && tok !== end) {
        if (tok === " ") {
          if (override) {
            overrides.push(override);
            override = "";
          }
        } else if (tok === "/" && peek() === "/") {
          break;
        } else {
          override += tok;
        }
        next();
      }
      if (override) {
        overrides.push(override);
      }
      return overrides;
    }
    function pComment(end) {
      next();
      next();
      let comment = "";
      while (tok && tok !== "\n" && tok !== end) {
        comment += tok;
        next();
      }
      if (tok && tok === "\n") {
        next();
      }
      return new Comment(comment, true);
    }
    function pLine() {
      let diff;
      const startLine = lineNo;
      if (tok === "+" || tok === "-") {
        diff = tok;
        next();
      }
      const block = pBlock();
      if (tok === "/" && peek() === "/") {
        const comment = pComment();
        comment.hasBlock = block && block.children.length;
        if (!comment.hasBlock) {
          comment.lineNo = startLine;
          return comment;
        }
        block.comment = comment;
      }
      if (block) {
        block.diff = diff;
        block.lineNo = startLine;
      }
      return block;
    }
    return () => {
      if (!tok) {
        return void 0;
      }
      const line = pLine();
      return line || "NL";
    };
  }
  function parseScripts(getLine) {
    let line = getLine();
    function next() {
      line = getLine();
    }
    function pFile() {
      while (line === "NL") {
        next();
      }
      const scripts = [];
      while (line) {
        let blocks = [];
        while (line && line !== "NL") {
          let b = pLine();
          const isGlow = b.diff === "+";
          if (isGlow) {
            b.diff = null;
          }
          if (b.isElse || b.isEnd) {
            const stray = new Block({ ...b.info, shape: "stack" }, b.children);
            stray.lineNo = b.lineNo;
            stray.stray = b.isEnd ? "end" : "else";
            b = stray;
          }
          if (isGlow) {
            const last = blocks[blocks.length - 1];
            let children = [];
            if (last && last.isGlow) {
              blocks.pop();
              children = last.child.isScript ? last.child.blocks : [last.child];
            }
            children.push(b);
            blocks.push(new Glow(new Script(children)));
          } else if (b.isHat) {
            if (blocks.length) {
              scripts.push(new Script(blocks));
            }
            blocks = [b];
          } else if (b.isFinal) {
            blocks.push(b);
            break;
          } else if (b.isCommand) {
            blocks.push(b);
          } else {
            if (blocks.length) {
              scripts.push(new Script(blocks));
            }
            scripts.push(new Script([b]));
            blocks = [];
            break;
          }
        }
        if (blocks.length) {
          scripts.push(new Script(blocks));
        }
        while (line === "NL") {
          next();
        }
      }
      return scripts;
    }
    function pLine() {
      const b = line;
      next();
      if (b.hasScript) {
        while (true) {
          const blocks = pMouth();
          b.children.push(new Script(blocks));
          if (line && line.isElse) {
            for (const child of line.children) {
              b.children.push(child);
            }
            next();
            continue;
          }
          if (line && line.isEnd) {
            next();
          } else {
            b.unclosed = true;
          }
          break;
        }
      }
      return b;
    }
    function pMouth() {
      const blocks = [];
      while (line) {
        if (line === "NL") {
          next();
          continue;
        }
        if (!line.isCommand) {
          return blocks;
        }
        const b = pLine();
        const isGlow = b.diff === "+";
        if (isGlow) {
          b.diff = null;
        }
        if (isGlow) {
          const last = blocks[blocks.length - 1];
          let children = [];
          if (last && last.isGlow) {
            blocks.pop();
            children = last.child.isScript ? last.child.blocks : [last.child];
          }
          children.push(b);
          blocks.push(new Glow(new Script(children)));
        } else {
          blocks.push(b);
        }
      }
      return blocks;
    }
    return pFile();
  }
  function eachBlock(x, cb) {
    if (x.isScript) {
      x.blocks = x.blocks.map((block) => {
        eachBlock(block, cb);
        return cb(block) || block;
      });
    } else if (x.isBlock) {
      x.children = x.children.map((child) => {
        eachBlock(child, cb);
        return cb(child) || child;
      });
    } else if (x.isGlow) {
      eachBlock(x.child, cb);
    }
  }
  var listBlocks = {
    "append:toList:": 1,
    "deleteLine:ofList:": 1,
    "insert:at:ofList:": 2,
    "setLine:ofList:to:": 1,
    "showList:": 0,
    "hideList:": 0
  };
  function recogniseStuff(scripts) {
    const customBlocksByHash = /* @__PURE__ */ Object.create(null);
    const listNames = /* @__PURE__ */ new Set();
    scripts.forEach((script) => {
      const customArgs = /* @__PURE__ */ new Set();
      eachBlock(script, (block) => {
        if (!block.isBlock) {
          return;
        }
        if (block.info.shape === "define-hat" || block.info.shape === "define-cat") {
          const outline = block.children.find((child) => child.isOutline);
          if (!outline) {
            return;
          }
          const names = [];
          const parts = [];
          for (const child of outline.children) {
            if (child.isLabel) {
              parts.push(child.value.replace(/%/g, ""));
            } else if (child.isBlock) {
              if (!child.info.argument) {
                return;
              }
              parts.push(
                {
                  number: "%n",
                  string: "%s",
                  boolean: "%b"
                }[child.info.argument]
              );
              const name = blockName(child);
              names.push(name);
              customArgs.add(name);
            }
          }
          const spec = parts.join(" ");
          const hash = hashSpec(spec);
          const info = {
            spec,
            names
          };
          if (!customBlocksByHash[hash]) {
            customBlocksByHash[hash] = info;
          }
          block.info.id = "PROCEDURES_DEFINITION";
          block.info.selector = "procDef";
          block.info.call = info.spec;
          block.info.names = info.names;
          block.info.category = "custom";
        } else if (block.info.categoryIsDefault && (block.isReporter || block.isBoolean)) {
          const name = blockName(block);
          if (customArgs.has(name)) {
            block.info.category = "custom-arg";
            block.info.categoryIsDefault = false;
            block.info.selector = "getParam";
          }
        } else if (Object.prototype.hasOwnProperty.call(listBlocks, block.info.selector)) {
          const argIndex = listBlocks[block.info.selector];
          const inputs = block.children.filter((child) => !child.isLabel);
          const input = inputs[argIndex];
          if (input && input.isInput) {
            listNames.add(input.value);
          }
        }
      });
    });
    scripts.forEach((script) => {
      eachBlock(script, (block) => {
        if (block.info && block.info.categoryIsDefault && block.info.category === "obsolete") {
          const info2 = customBlocksByHash[block.info.hash];
          if (info2) {
            block.info.id = "PROCEDURES_CALL";
            block.info.selector = "call";
            block.info.call = info2.spec;
            block.info.names = info2.names;
            block.info.category = "custom";
          }
          return;
        }
        let name, info;
        if (block.isReporter && block.info.category === "variables" && block.info.categoryIsDefault) {
          block.info.selector = "readVariable";
          name = blockName(block);
          info = block.info;
        }
        if (!name) {
          return;
        }
        if (listNames.has(name)) {
          info.category = "list";
          info.categoryIsDefault = false;
          info.selector = "contentsOfList:";
        }
        return;
      });
    });
  }
  function parse(code, options) {
    options = {
      inline: false,
      languages: ["en"],
      ...options
    };
    if (options.dialect) {
      throw new Error("Option 'dialect' no longer supported");
    }
    code = code.replace(/&lt;/g, "<");
    code = code.replace(/&gt;/g, ">");
    if (options.inline) {
      code = code.replace(/\n/g, " ");
    }
    const languages = options.languages.map((code2) => {
      const lang = allLanguages[code2];
      if (!lang) {
        throw new Error(`Unknown language: '${code2}'`);
      }
      return lang;
    });
    const f = parseLines(code, languages);
    const scripts = parseScripts(f);
    recogniseStuff(scripts);
    return new Document(scripts);
  }

  // scripts/blockcoding/js-reference/vendor/scratchblocks/zh-cn.json
  var zh_cn_default = {
    commands: {
      MOTION_MOVESTEPS: "\u79FB\u52A8 %1 \u6B65",
      MOTION_TURNRIGHT: "\u53F3\u8F6C @turnRight %1 \u5EA6",
      MOTION_TURNLEFT: "\u5DE6\u8F6C @turnLeft %1 \u5EA6",
      MOTION_POINTINDIRECTION: "\u9762\u5411 %1 \u65B9\u5411",
      MOTION_POINTTOWARDS: "\u9762\u5411 %1",
      MOTION_GOTOXY: "\u79FB\u5230 x: %1 y: %2",
      MOTION_GOTO: "\u79FB\u5230 %1",
      MOTION_GLIDESECSTOXY: "\u5728 %1 \u79D2\u5185\u6ED1\u884C\u5230 x: %2 y: %3",
      MOTION_GLIDETO: "\u5728 %1 \u79D2\u5185\u6ED1\u884C\u5230 %2",
      MOTION_CHANGEXBY: "\u5C06x\u5750\u6807\u589E\u52A0 %1",
      MOTION_SETX: "\u5C06x\u5750\u6807\u8BBE\u4E3A %1",
      MOTION_CHANGEYBY: "\u5C06y\u5750\u6807\u589E\u52A0 %1",
      MOTION_SETY: "\u5C06y\u5750\u6807\u8BBE\u4E3A %1",
      MOTION_SETROTATIONSTYLE: "\u5C06\u65CB\u8F6C\u65B9\u5F0F\u8BBE\u4E3A %1",
      LOOKS_SAYFORSECS: "\u8BF4 %1 %2 \u79D2",
      LOOKS_SAY: "\u8BF4 %1",
      LOOKS_THINKFORSECS: "\u601D\u8003 %1 %2 \u79D2",
      LOOKS_THINK: "\u601D\u8003 %1",
      LOOKS_SHOW: "\u663E\u793A",
      LOOKS_HIDE: "\u9690\u85CF",
      LOOKS_SWITCHCOSTUMETO: "\u6362\u6210 %1 \u9020\u578B",
      LOOKS_NEXTCOSTUME: "\u4E0B\u4E00\u4E2A\u9020\u578B",
      LOOKS_NEXTBACKDROP_BLOCK: "\u4E0B\u4E00\u4E2A\u80CC\u666F",
      LOOKS_SWITCHBACKDROPTO: "\u6362\u6210 %1 \u80CC\u666F",
      LOOKS_SWITCHBACKDROPTOANDWAIT: "\u6362\u6210 %1 \u80CC\u666F\u5E76\u7B49\u5F85",
      LOOKS_CHANGEEFFECTBY: "\u5C06 %1 \u7279\u6548\u589E\u52A0 %2",
      LOOKS_SETEFFECTTO: "\u5C06 %1 \u7279\u6548\u8BBE\u5B9A\u4E3A %2",
      LOOKS_CLEARGRAPHICEFFECTS: "\u6E05\u9664\u56FE\u5F62\u7279\u6548",
      LOOKS_CHANGESIZEBY: "\u5C06\u5927\u5C0F\u589E\u52A0 %1",
      LOOKS_SETSIZETO: "\u5C06\u5927\u5C0F\u8BBE\u4E3A %1",
      LOOKS_GOTOFRONTBACK: "\u79FB\u5230\u6700 %1",
      LOOKS_GOFORWARDBACKWARDLAYERS: "%1 %2 \u5C42",
      SOUND_PLAY: "\u64AD\u653E\u58F0\u97F3 %1",
      SOUND_CHANGEEFFECTBY: "\u5C06 %1 \u97F3\u6548\u589E\u52A0 %2",
      SOUND_SETEFFECTO: "\u5C06 %1 \u97F3\u6548\u8BBE\u4E3A %2",
      SOUND_CLEAREFFECTS: "\u6E05\u9664\u97F3\u6548",
      SOUND_PLAYUNTILDONE: "\u64AD\u653E\u58F0\u97F3 %1 \u7B49\u5F85\u64AD\u5B8C",
      SOUND_STOPALLSOUNDS: "\u505C\u6B62\u6240\u6709\u58F0\u97F3",
      "music.playDrumForBeats": "\u51FB\u6253 %1 %2 \u62CD",
      "music.restForBeats": "\u4F11\u6B62%1\u62CD",
      "music.playNoteForBeats": "\u6F14\u594F\u97F3\u7B26%1%2\u62CD",
      "music.setInstrument": "\u5C06\u4E50\u5668\u8BBE\u4E3A %1",
      SOUND_CHANGEVOLUMEBY: "\u5C06\u97F3\u91CF\u589E\u52A0 %1",
      SOUND_SETVOLUMETO: "\u5C06\u97F3\u91CF\u8BBE\u4E3A %1%",
      "music.changeTempo": "\u5C06\u6F14\u594F\u901F\u5EA6\u589E\u52A0%1",
      "music.setTempo": "\u5C06\u6F14\u594F\u901F\u5EA6\u8BBE\u5B9A\u4E3A%1",
      "pen.clear": "\u5168\u90E8\u64E6\u9664",
      "pen.stamp": "\u56FE\u7AE0",
      "pen.penDown": "\u843D\u7B14",
      "pen.penUp": "\u62AC\u7B14",
      "pen.setColor": "\u5C06\u7B14\u7684\u989C\u8272\u8BBE\u4E3A%1",
      "pen.changeHue": "\u5C06\u7B14\u7684\u989C\u8272\u589E\u52A0%1",
      "pen.setColorParam": "\u5C06\u7B14\u7684 %1 \u8BBE\u4E3A%2",
      "pen.changeColorParam": "\u5C06\u7B14\u7684 %1 \u589E\u52A0%2",
      "pen.setHue": "\u5C06\u7B14\u7684\u989C\u8272\u8BBE\u4E3A%1",
      "pen.changeShade": "\u5C06\u7B14\u7684\u4EAE\u5EA6\u589E\u52A0%1",
      "pen.setShade": "\u5C06\u7B14\u7684\u4EAE\u5EA6\u8BBE\u4E3A%1",
      "pen.changeSize": "\u5C06\u7B14\u7684\u7C97\u7EC6\u589E\u52A0%1",
      "pen.setSize": "\u5C06\u7B14\u7684\u7C97\u7EC6\u8BBE\u4E3A%1",
      EVENT_WHENFLAGCLICKED: "\u5F53 @greenFlag \u88AB\u70B9\u51FB",
      EVENT_WHENKEYPRESSED: "\u5F53\u6309\u4E0B %1 \u952E",
      EVENT_WHENTHISSPRITECLICKED: "\u5F53\u89D2\u8272\u88AB\u70B9\u51FB",
      EVENT_WHENSTAGECLICKED: "\u5F53\u821E\u53F0\u88AB\u70B9\u51FB",
      EVENT_WHENBACKDROPSWITCHESTO: "\u5F53\u80CC\u666F\u6362\u6210 %1",
      EVENT_WHENGREATERTHAN: "\u5F53 %1 > %2",
      EVENT_WHENBROADCASTRECEIVED: "\u5F53\u63A5\u6536\u5230 %1",
      EVENT_BROADCAST: "\u5E7F\u64AD %1",
      EVENT_BROADCASTANDWAIT: "\u5E7F\u64AD %1 \u5E76\u7B49\u5F85",
      CONTROL_WAIT: "\u7B49\u5F85 %1 \u79D2",
      CONTROL_REPEAT: "\u91CD\u590D\u6267\u884C %1 \u6B21",
      CONTROL_FOREVER: "\u91CD\u590D\u6267\u884C",
      CONTROL_IF: "\u5982\u679C %1 \u90A3\u4E48",
      CONTROL_WAITUNTIL: "\u7B49\u5F85 %1",
      CONTROL_REPEATUNTIL: "\u91CD\u590D\u6267\u884C\u76F4\u5230 %1",
      CONTROL_STOP: "\u505C\u6B62 %1",
      CONTROL_STARTASCLONE: "\u5F53\u4F5C\u4E3A\u514B\u9686\u4F53\u542F\u52A8\u65F6",
      CONTROL_CREATECLONEOF: "\u514B\u9686 %1",
      CONTROL_DELETETHISCLONE: "\u5220\u9664\u6B64\u514B\u9686\u4F53",
      SENSING_ASKANDWAIT: "\u8BE2\u95EE %1 \u5E76\u7B49\u5F85",
      "videoSensing.videoToggle": "%1\u6444\u50CF\u5934",
      "videoSensing.setVideoTransparency": "\u5C06\u89C6\u9891\u900F\u660E\u5EA6\u8BBE\u4E3A%1",
      "videoSensing.whenMotionGreaterThan": "\u5F53\u89C6\u9891\u8FD0\u52A8 > %1",
      SENSING_RESETTIMER: "\u8BA1\u65F6\u5668\u5F52\u96F6",
      DATA_SETVARIABLETO: "\u5C06 %1 \u8BBE\u4E3A %2",
      DATA_CHANGEVARIABLEBY: "\u5C06 %1 \u589E\u52A0 %2",
      DATA_SHOWVARIABLE: "\u663E\u793A\u53D8\u91CF %1",
      DATA_HIDEVARIABLE: "\u9690\u85CF\u53D8\u91CF %1",
      DATA_ADDTOLIST: "\u5C06 %1 \u52A0\u5165 %2",
      DATA_DELETEOFLIST: "\u5220\u9664 %2 \u7684\u7B2C %1 \u9879",
      DATA_DELETEALLOFLIST: "\u5220\u9664 %1 \u7684\u5168\u90E8\u9879\u76EE",
      MOTION_IFONEDGEBOUNCE: "\u78B0\u5230\u8FB9\u7F18\u5C31\u53CD\u5F39",
      DATA_INSERTATLIST: "\u5728 %3 \u7684\u7B2C %2 \u9879\u524D\u63D2\u5165 %1",
      DATA_REPLACEITEMOFLIST: "\u5C06 %2 \u7684\u7B2C %1 \u9879\u66FF\u6362\u4E3A %3",
      DATA_SHOWLIST: "\u663E\u793A\u5217\u8868 %1",
      DATA_HIDELIST: "\u9690\u85CF\u5217\u8868 %1",
      MOTION_XPOSITION: "x \u5750\u6807",
      MOTION_YPOSITION: "y \u5750\u6807",
      MOTION_DIRECTION: "\u65B9\u5411",
      SENSING_OF_COSTUMENUMBER: "\u9020\u578B\u7F16\u53F7",
      LOOKS_COSTUMENUMBERNAME: "\u9020\u578B %1",
      LOOKS_SIZE: "\u5927\u5C0F",
      SENSING_OF_BACKDROPNAME: "\u80CC\u666F\u540D\u79F0",
      LOOKS_BACKDROPNUMBERNAME: "\u80CC\u666F %1",
      SENSING_OF_BACKDROPNUMBER: "\u80CC\u666F\u7F16\u53F7",
      SOUND_VOLUME: "\u97F3\u91CF",
      "music.getTempo": "\u6F14\u594F\u901F\u5EA6",
      SENSING_TOUCHINGOBJECT: "\u78B0\u5230 %1 ?",
      SENSING_TOUCHINGCOLOR: "\u78B0\u5230\u989C\u8272 %1 ?",
      SENSING_COLORISTOUCHINGCOLOR: "\u989C\u8272 %1 \u78B0\u5230 %2 ?",
      SENSING_DISTANCETO: "\u5230 %1 \u7684\u8DDD\u79BB",
      SENSING_ANSWER: "\u56DE\u7B54",
      SENSING_KEYPRESSED: "\u6309\u4E0B %1 \u952E?",
      SENSING_MOUSEDOWN: "\u6309\u4E0B\u9F20\u6807?",
      SENSING_MOUSEX: "\u9F20\u6807\u7684x\u5750\u6807",
      SENSING_MOUSEY: "\u9F20\u6807\u7684y\u5750\u6807",
      SENSING_SETDRAGMODE: "\u5C06\u62D6\u52A8\u6A21\u5F0F\u8BBE\u4E3A %1",
      SENSING_LOUDNESS: "\u54CD\u5EA6",
      "videoSensing.videoOn": "\u76F8\u5BF9\u4E8E%2\u7684\u89C6\u9891%1",
      SENSING_TIMER: "\u8BA1\u65F6\u5668",
      SENSING_OF: "%2 \u7684 %1",
      SENSING_CURRENT: "\u5F53\u524D\u65F6\u95F4\u7684 %1",
      SENSING_DAYSSINCE2000: "2000\u5E74\u81F3\u4ECA\u7684\u5929\u6570",
      SENSING_ONLINE: "is online?",
      SENSING_USERNAME: "\u7528\u6237\u540D",
      OPERATORS_ADD: "%1 + %2",
      OPERATORS_SUBTRACT: "%1 - %2",
      OPERATORS_MULTIPLY: "%1 * %2",
      OPERATORS_DIVIDE: "%1 / %2",
      OPERATORS_RANDOM: "\u5728 %1 \u548C %2 \u4E4B\u95F4\u53D6\u968F\u673A\u6570",
      OPERATORS_LT: "%1 < %2",
      OPERATORS_EQUALS: "%1 = %2",
      OPERATORS_GT: "%1 > %2",
      OPERATORS_AND: "%1 \u4E0E %2",
      OPERATORS_OR: "%1 \u6216 %2",
      OPERATORS_NOT: "%1 \u4E0D\u6210\u7ACB",
      OPERATORS_JOIN: "\u8FDE\u63A5 %1 \u548C %2",
      OPERATORS_LETTEROF: "%2 \u7684\u7B2C %1 \u4E2A\u5B57\u7B26",
      OPERATORS_LENGTH: "%1 \u7684\u5B57\u7B26\u6570",
      OPERATORS_MOD: "%1 \u9664\u4EE5 %2 \u7684\u4F59\u6570",
      OPERATORS_ROUND: "\u56DB\u820D\u4E94\u5165 %1",
      OPERATORS_MATHOP: "%1 %2",
      OPERATORS_CONTAINS: "%1 \u5305\u542B %2 ?",
      DATA_ITEMOFLIST: "%2 \u7684\u7B2C %1 \u9879",
      DATA_ITEMNUMOFLIST: "%2 \u4E2D\u7B2C\u4E00\u4E2A %1 \u7684\u7F16\u53F7",
      DATA_LENGTHOFLIST: "%1 \u7684\u9879\u76EE\u6570",
      DATA_LISTCONTAINSITEM: "%1 \u5305\u542B %2 ?",
      CONTROL_ELSE: "\u5426\u5219",
      SENSING_USERID: "\u7528\u6237id",
      SENSING_LOUD: "\u54CD\u58F0\uFF1F",
      "faceSensing.goToPart": "go to %1",
      "faceSensing.pointInFaceTiltDirection": "point in direction of face tilt",
      "faceSensing.setSizeToFaceSize": "set size to face size",
      "faceSensing.whenTilted": "when face tilts %1",
      "faceSensing.whenSpriteTouchesPart": "when this sprite touches a %1",
      "faceSensing.whenFaceDetected": "when a face is detected",
      "faceSensing.faceDetected": "a face is detected?",
      "faceSensing.faceTilt": "face tilt",
      "faceSensing.faceSize": "face size",
      "text2speech.speakAndWaitBlock": "\u6717\u8BFB %1",
      "text2speech.setVoiceBlock": "\u4F7F\u7528 %1 \u55D3\u97F3",
      "text2speech.setLanguageBlock": "\u5C06\u6717\u8BFB\u8BED\u8A00\u8BBE\u7F6E\u4E3A%1",
      "translate.translateBlock": "\u5C06 %1 \u8BD1\u4E3A %2",
      "translate.viewerLanguage": "\u8BBF\u5BA2\u8BED\u8A00",
      "makeymakey.whenKeyPressed": "\u5F53\u6309\u4E0B %1 \u952E",
      "makeymakey.whenKeysPressedInOrder": "\u5F53\u4F9D\u6B21\u6309\u4E0B%1\u952E\u65F6",
      "microbit.whenButtonPressed": "\u5F53\u6309\u4E0B%1\u6309\u94AE",
      "microbit.isButtonPressed": "\u6309\u4E0B%1\u6309\u94AE\uFF1F",
      "microbit.whenGesture": "\u5F53\u88AB%1",
      "microbit.displaySymbol": "\u663E\u793A%1",
      "microbit.displayText": "\u663E\u793A\u6587\u672C%1",
      "microbit.clearDisplay": "\u6E05\u7A7A\u5C4F\u5E55",
      "microbit.whenTilted": "\u5F53\u5411%1\u503E\u659C",
      "microbit.isTilted": "\u5411%1\u503E\u659C\uFF1F",
      "microbit.tiltAngle": "\u5411%1\u503E\u89D2",
      "microbit.whenPinConnected": "\u5F53\u5F15\u811A%1\u63A5\u5730",
      "ev3.motorTurnClockwise": "\u9A6C\u8FBE%1\u6B63\u8F6C%2\u79D2",
      "ev3.motorTurnCounterClockwise": "\u9A6C\u8FBE%1\u53CD\u8F6C%2\u79D2",
      "ev3.motorSetPower": "\u9A6C\u8FBE%1\u529F\u7387\u8BBE\u4E3A%2%",
      "ev3.getMotorPosition": "\u9A6C\u8FBE%1\u7684\u4F4D\u7F6E",
      "ev3.whenButtonPressed": "\u5F53\u6309\u4E0B\u6309\u94AE%1",
      "ev3.whenDistanceLessThan": "\u5F53\u8DDD\u79BB < %1",
      "ev3.whenBrightnessLessThan": "\u5F53\u4EAE\u5EA6 < %1",
      "ev3.buttonPressed": "\u6309\u4E0B\u6309\u94AE%1\uFF1F",
      "ev3.getDistance": "\u8DDD\u79BB",
      "ev3.getBrightness": "\u4EAE\u5EA6",
      "ev3.beepNote": "\u9E23\u7B1B%1%2\u79D2",
      "wedo2.motorOn": "\u5F00\u542F%1",
      "wedo2.motorOff": "\u5173\u95ED%1",
      "wedo2.startMotorPower": "\u5C06%1\u529F\u7387\u8BBE\u4E3A%2",
      "wedo2.setMotorDirection": "\u5C06%1\u8F6C\u5411\u8BBE\u4E3A%2",
      "wedo2.whenDistance": "\u5F53\u8DDD\u79BB%1%2",
      "wedo2.getDistance": "\u8DDD\u79BB",
      "wedo2.motorOnFor": "\u5F00\u542F%1%2\u79D2",
      "wedo2.setLightHue": "\u5C06\u706F\u5149\u989C\u8272\u8BBE\u4E3A%1",
      "wedo2.playNoteFor": "\u6F14\u594F\u97F3\u7B26%1%2\u79D2",
      "wedo2.whenTilted": "\u5F53\u5411%1\u503E\u659C",
      "wedo2.isTilted": "\u5411%1\u503E\u659C\uFF1F",
      "wedo2.getTiltAngle": "\u5411%1\u503E\u89D2",
      "gdxfor.whenGesture": "\u5F53\u4F20\u611F\u5668%1",
      "gdxfor.whenForcePushedOrPulled": "\u5F53\u529B\u4F20\u611F\u5668\u88AB%1",
      "gdxfor.getForce": "\u53D7\u529B",
      "gdxfor.whenTilted": "\u5F53\u5411%1\u503E\u659C",
      "gdxfor.isTilted": "\u5411%1\u503E\u659C\uFF1F",
      "gdxfor.getTilt": "\u5411%1\u503E\u89D2",
      "gdxfor.isFreeFalling": "\u81EA\u7531\u843D\u4F53\uFF1F",
      "gdxfor.getSpin": "\u5411%1\u7684\u65CB\u8F6C\u901F\u5EA6",
      "gdxfor.getAcceleration": "\u5411%1\u7684\u52A0\u901F\u5EA6",
      "boost.motorOnFor": "\u4F7F\u9A6C\u8FBE %1\u8F6C\u52A8 %2 \u79D2",
      "boost.motorOnForRotation": "\u4F7F\u9A6C\u8FBE %1\u8F6C\u52A8 %2 \u5708",
      "boost.motorOn": "\u5F00\u542F\u9A6C\u8FBE %1",
      "boost.motorOff": "\u5173\u95ED\u9A6C\u8FBE %1",
      "boost.setMotorPower": "\u5C06\u9A6C\u8FBE %1 \u901F\u5EA6\u8BBE\u4E3A %2%",
      "boost.setMotorDirection": "\u5C06\u9A6C\u8FBE%1 \u65B9\u5411\u8BBE\u4E3A %2",
      "boost.getMotorPosition": "\u9A6C\u8FBE %1 \u4F4D\u7F6E",
      "boost.whenColor": "\u5F53\u770B\u5230%1\u79EF\u6728",
      "boost.seeingColor": "\u770B\u5230 %1\u79EF\u6728 ?",
      "boost.whenTilted": "\u5F53\u5411%1\u503E\u659C",
      "boost.getTiltAngle": "\u5411%1\u503E\u89D2",
      "boost.setLightHue": "\u5C06\u706F\u5149\u989C\u8272\u8BBE\u4E3A%1"
    },
    dropdowns: {},
    ignorelt: [],
    faceParts: [
      "nose",
      "mouth",
      "left eye",
      "right eye",
      "between eyes",
      "left ear",
      "right ear",
      "top of head"
    ],
    soundEffects: [
      "\u97F3\u8C03",
      "\u5DE6\u53F3\u5E73\u8861"
    ],
    microbitWhen: [
      "\u79FB\u52A8",
      "\u6643\u52A8",
      "\u629B\u8D77"
    ],
    osis: [
      "\u8BE5\u89D2\u8272\u7684\u5176\u4ED6\u811A\u672C"
    ],
    definePrefix: [
      "\u5B9A\u4E49"
    ],
    defineSuffix: [],
    palette: {
      Motion: "\u8FD0\u52A8",
      Looks: "\u5916\u89C2",
      Sound: "\u58F0\u97F3",
      Events: "\u4E8B\u4EF6",
      Control: "\u63A7\u5236",
      Sensing: "\u4FA6\u6D4B",
      Operators: "\u8FD0\u7B97",
      Variables: "\u53D8\u91CF",
      "My Blocks": "\u81EA\u5236\u79EF\u6728"
    },
    math: [
      "\u7EDD\u5BF9\u503C",
      "\u5411\u4E0B\u53D6\u6574",
      "\u5411\u4E0A\u53D6\u6574",
      "\u5E73\u65B9\u6839",
      "sin",
      "cos",
      "tan",
      "asin",
      "acos",
      "atan",
      "ln",
      "log",
      "e ^",
      "10 ^"
    ],
    aliases: {
      "\u5DE6\u8F6C %1 \u5EA6": "MOTION_TURNLEFT",
      "\u53F3\u8F6C %1 \u5EA6": "MOTION_TURNRIGHT",
      \u5F53\u7EFF\u65D7\u88AB\u70B9\u51FB: "EVENT_WHENFLAGCLICKED",
      \u70B9\u51FB\u7EFF\u65D7\u65F6: "EVENT_WHENFLAGCLICKED",
      \u7ED3\u675F: "scratchblocks:end"
    },
    name: "\u7B80\u4F53\u4E2D\u6587",
    percentTranslated: 100
  };

  // scripts/blockcoding/sb-adapter.js
  loadLanguages({ "zh-cn": zh_cn_default });
  function serialize(node) {
    if (node == null) return null;
    if (node.isGlow) {
      return { type: "glow", child: serialize(node.child) };
    }
    if (node.isScript) {
      return { type: "script", blocks: node.blocks.map(serialize) };
    }
    if (node.isBlock) {
      return {
        type: "block",
        info: { ...node.info },
        children: node.children.map(serialize),
        comment: node.comment ? serialize(node.comment) : null,
        lineNo: node.lineNo ?? null,
        unclosed: Boolean(node.unclosed),
        stray: node.stray ?? null
      };
    }
    if (node.isInput) {
      return {
        type: "input",
        shape: node.shape,
        value: typeof node.value === "string" ? node.value : null,
        menu: node.menu ?? null
      };
    }
    if (node.isLabel) {
      return { type: "label", value: node.value, cls: node.cls ?? null };
    }
    if (node.isIcon) {
      return { type: "icon", name: node.name };
    }
    if (node.isComment) {
      return { type: "comment", value: node.value, hasBlock: Boolean(node.hasBlock) };
    }
    return { type: "unknown" };
  }
  function stripCommentLines(code) {
    const kept = [];
    const originalLineOf = [];
    String(code).split("\n").forEach((line, index) => {
      if (/^\s*\/\//.test(line)) return;
      kept.push(line);
      originalLineOf.push(index + 1);
    });
    return { text: kept.join("\n"), originalLineOf };
  }
  function remapLines(node, originalLineOf, parentLine = null) {
    if (node == null) return;
    if (node.lineNo == null) {
      if (node.type === "block") node.lineNo = parentLine;
    } else {
      node.lineNo = originalLineOf[node.lineNo - 1] ?? node.lineNo;
    }
    const line = node.type === "block" ? node.lineNo : parentLine;
    if (node.children) node.children.forEach((child) => remapLines(child, originalLineOf, line));
    if (node.blocks) node.blocks.forEach((block) => remapLines(block, originalLineOf, line));
    if (node.child) remapLines(node.child, originalLineOf, line);
  }
  globalThis.ScratchblocksJson = {
    parse(code, languagesCsv) {
      const { text, originalLineOf } = stripCommentLines(code);
      const doc = parse(text, {
        languages: String(languagesCsv || "en").split(",")
      });
      const scripts = doc.scripts.map(serialize);
      scripts.forEach((script) => remapLines(script, originalLineOf));
      return JSON.stringify({ scripts });
    }
  };
})();
