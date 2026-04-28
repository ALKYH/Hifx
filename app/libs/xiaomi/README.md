 
目录
DynamicEffect	2
概述	2
常量	3
INTENSITY	3
SHARPNESS	3
公有方法	3
startCompose	3
createTransient	4
createContinuous	4
createParameter	5
addPrimitive	5
addParameter	6
create	6
PrimitiveEffect	7
概述	7
公有方法	7
addParameter	7
Parameter	8
HapticPlayer	8
概述	8
公共构造器	9
HapticPlayer	9
HapticPlayer	10
公有方法	10
isAvailable	10
getVersion*	10
start	11
start	11
start	11
start	12
pause*	12
seekTo*	13
stop	13

 
线性马达能力

DynamicEffect
public class DynamicEffect 
extends VibrationEffect implements Parcelable
java.lang.Object
   ↳	android.os.DynamicEffect

DynamicEffect 是由一组参数所描述的振动效果，它可以通过HapticPlayer 进行播放。
概述
嵌套类	
DynamicEffect.PrimitiveEffect	基础振动效果，分为“短暂”或者“连续”，描述一个振动冲击或者一段时间的连续振动。
DynamicEffect.Parameter	控制基础振动效果的强度或者锐度。

常量	
int	控制振动效果的强度
int	控制振动效果的锐度

公有方法	
DynamicEffect	startCompose()
实例化一个DynamicEffect对象。
static PrimitiveEffect	createTransient(float intensity, float sharpness)
创建一个瞬间的振动冲击。
static PrimitiveEffect	createContinuous(float intensity, float sharpness, float duration)
创建一段连续的振动效果。
static Parameter	createParameter(int type, float [] timings, float [] values)
创建一组控制强度或锐度的参数。
static DynamicEffect	create (String jsonArray)
从字符串所描述的json信息中创建一个DynamicEffect实例
DynamicEffect	addPrimitive (float startTime, PrimitiveEffect effect)
在指定时间添加一个基础效果。
DynamicEffect	addParameter(float start, Parameter param)
在指定时间添加控制参数。

常量
INTENSITY
public static final int INTENSIT
指定 Parameter 所控制的类型为振动强度。
常量值：0 
SHARPNESS
public static final int SHARPNESS
指定 Parameter 所控制的类型为振动锐度。
常量值：1
公有方法
startCompose
public static DynamicEffect startCompose ()
实例化一个DynamicEffect对象，可用于组合多个PrimitiveEffect。
返回值	
DynamicEffect	DynamicEffect实例,可用来添加基础效果或者控制参数
createTransient
public static PrimitiveEffect createTransient(float intensity, float sharpness)
创建一个瞬间的振动冲击。
参数	
intensity	float:瞬时振动的强度。范围在0f和1f之间
sharpness	float:瞬时振动的锐度。范围在0f和1f之间


返回值	
DynamicEffect.PrimitiveEffect	一个描述瞬时振动的PrimitiveEffect对象

createContinuous
public static PrimitiveEffect createContinuous(float intensity, float sharpness, float duration)
创建一段连续的振动效果。


intensity	float:连续振动的强度。范围在0f和1f之间
sharpness	float:连续振动的锐度。范围在0f和1f之间
duration	float:连续振动的时长。时间单位为秒。

返回值	
DynamicEffect.PrimitiveEffect	一个描述连续振动的PrimitiveEffect对象
createParameter
public static Parameter createParameter(int type, float [] timings, float [] values)
创建一组控制强度或锐度的参数。控制点由一系列的时间与值组成。可以理解在时间轴上定义一些散点，当只有一个点时，直接改变当前参数，当有大于等于两个点时，会将这些点连起来并作用于当前的基础效果。对于强度控制，Parameter 中的值会与原 PrimitiveEffect 的强度作乘积进行缩放, 因此请务必保证类型为 INTENSITY 类型的Parameter值范围在 0-1 之间。
对于锐度控制，Parameter 中的值会与原 PrimitiveEffect 的锐度作加减，因此该类型 Parameter 的值范围在 [-1,+1]，作用后的锐度如果超出 [0,1] 的范围，取最值。
例如，type：INTENSITY；timings：0，１；values：1，0。描述了一条时间长度为 1 秒的直线，该直线的起始值为 1，终止值为 0，并且该直线所作用的属性为强度。


type	int:参数所控制的类型，可选值为常量 INTENSITY 或 SHARPNESS
timings	float[]:一系列时间点，单位为秒，总数量应当与值的总数量相同
values	float[]:一系列值，范围在-1f与1f之间。总数量应当与时间点的总数量相同




DynamicEffect.Parameter	返回一个控制强度或者锐度的参数对象
addPrimitive
public DynamicEffect addPrimitive(float startTime, PrimitiveEffect effect)
在指定时间添加一个基础效果。


startTime	float：基础效果的起始时间，也是相对于整个 DynamicEffect 的偏移时间。单位为秒
effect	PrimitiveEffect：基础振动效果




DynamicEffect	返回添加振动效果后的DynamicEffect实例
addParameter
public DynamicEffect addParameter(float start, Parameter param)
在指定时间添加控制参数，该参数会应用到指定时间点所有活跃的PrimitiveEffect上，可以理解为全局参数，全局参数会和PrimitiveEffect自身的参数值相互作用。对于 INTENSITY 控制，全局参数会与活跃的PrimitiveEffect的强度相乘；对于SHARPNESS控制，全局参数会与活跃的PrimitiveEffect的锐度相加减。
参数	
startTime	float:参数的起始时间，也是相对于整个 DynamicEffect 的偏移时间。单位为秒
param	Parameter:控制强度或锐度的参数实例


返回值	
DynamicEffect	返回添加参数后的 DynamicEffect 实例
create
public static DynamicEffect create (String jsonArray)
从字符串所描述的json信息中创建一个DynamicEffect实例，该实例可直接通过HapticPlayer进行播放。该实例经创建后不可添加基础效果。


jsonArray	json 字符串

返回值	
DynamicEffect	DynamicEffect：从json描述的文件中解析出的DynamicEffect实例
PrimitiveEffect
public static class DynamicEffect.PrimitiveEffect
java.lang.Object
   ↳	android.os.DynamicEffect.PrimitiveEffect

DynamicEffect 中的基本元素，分为Transient或Continuous两种基础的振动效果。
概述



PrimitiveEffect	addParameter(Parameter param)
为基础效果添加参数

公有方法
addParameter
public PrimitiveEffect addParameter(Parameter param)
为基础效果添加参数，该参数用于改变PrimitiveEffect的强度或锐度。当参数具有多个点时，受控制的参数会呈曲线进行渐变。注意：Parameter 只可作用于Continuous类型的PrimitiveEffect。


param	控制强度或锐度的参数实例

返回值	
PrimitiveEffect	返回添加参数后的PrimitiveEffect实例
Parameter
public static class DynamicEffect.Parameter
java.lang.Object
   ↳	android.os.DynamicEffect.Parameter

描述一组控制振动强度或锐度的参数，该参数可以当做DynamicEffect中的全局参数控制所有活跃的PrimitiveEffect，也可以当做单一Continuous类型的PrimitiveEffect的控制参数。
HapticPlayer
public class HapticPlayer
java.lang.Object
   ↳	android.os.HapticPlayer

HapticPlayer是用来播放DynamicEffect的工具类，它可以像播放器一样实现对 DynamicEffect的播放、暂停、跳转指定位置等操作。
概述
公共构造器
public HapticPlayer ()
构造一个未指定效果的HapticPlayer对象
public HapticPlayer (DynamicEffect effect)
构造一个HapticPlayer对象并指定播放的效果




static boolean	isAvailable()
返回是否支持DynamicEffect以及HapticPlayer功能，应用在使用前都应进行判断。
static String	getVersion()*
返回所支持的DynamicEffect以及HapticPlayer的版本信息
void	start()
开始或者恢复播放。如果之前的播放被暂停，该方法会从之前暂停的位置继续开始。如果之前的播放已经结束，调用该方法将不会重置已经结束的播放状态。播放DynamicEffect时会屏蔽除来电振动外其他所有场景的振动。
void	start(int times)
进行多次播放，次数由times所指定，当传入为-1时进行不间断的循环（系统可能对次数进行限制）
void	start (int times, int interval, int amplitude)
带有循环次数、两次循环间隔、振动整体幅度的start接口。
void	start(DynamicEffect effect)
立即播放DynamicEffect且无 pause/seekTo等需求。该操作会终止正在进行的DynamicEffect以及其他场景的振动。
void	pause()
暂停正在播放的DynamicEffect，调用start以继续。pause 中的DynamicEffect可以会使得系统能够处理普通场景的振动调用。
void	seekTo(long msec)
跳转到指定时间点。
void	stop()
停止正在播放的DynamicEffect。

公共构造器
HapticPlayer
public HapticPlayer ()
构造一个HapticPlayer对象并指定播放的效果，与使用无参数HapticPlayer构造方法后调用setDataSource (DynamicEffect)的产生的效果相同，或者使用start(DynamicEffect effect)直接进行播放。
HapticPlayer
public HapticPlayer (DynamicEffect effect)
构造一个HapticPlayer对象并指定播放的效果，与使用无参数HapticPlayer构造方法后调用setDataSource的产生的效果相同。


effect	描述振动效果的对象

公有方法
isAvailable
public static boolean isAvailable()
返回是否支持DynamicEffect以及HapticPlayer功能，应用在使用前都应进行判断。
返回值	
boolean	返回DynamicEffect以及HapticPlayer是否被该机器所支持
getVersion*
public static String getVersion()
返回所支持DynamicEffect以及HapticPlayer功能的版本信息。
Returns	
String	返回所支持的DynamicEffect以及HapticPlayer的版本信息
start
public void start()
开始或者恢复播放。如果之前的播放被暂停，该方法会从之前暂停的位置继续开始。如果之前的播放已经结束，调用该方法将不会重置已经结束的播放状态。播放DynamicEffect时会屏蔽除来电振动外其他所有场景的振动。
异常	
RemoteException	如果与VibratorService的通信发生异常
start
public void start(int times)
进行多次播放，次数由times所指定，当传入为-1时进行不间断的循环（系统可能对次数进行限制）
参数	
times	int:振动次数，传入-1表示进行系统所允许范围内最大次数的振动

异常	
RemoteException	如果与VibratorService的通信发生异常
start
public void start (int times, int interval, int amplitude) 
带有循环次数、两次循环间隔、振动整体幅度的start接口。


times	int:振动次数，传入-1表示进行系统所允许范围内最大次数的振动
interval	int: 控制循环振动之间的间隔时间，单位ms取值范围[0,1000]
amplitude	int:对振动的整体强度进行调整，取值范围[1,255]

异常	
RemoteException	如果与VibratorService的通信发生异常
start
public void start(DynamicEffect effect)
立即播放DynamicEffect且无pause/seekTo等需求。该操作会终止正在进行的DynamicEffect以及其他场景的振动。


effect	描述振动效果的对象

异常	
RemoteException	如果与VibratorService的通信发生异常
pause*
public void pause()
暂停正在播放的DynamicEffect，调用start以继续。pause中的DynamicEffect可以会使得系统能够处理普通场景的振动调用。


RemoteException	如果与VibratorService的通信发生异常
seekTo*
public void seekTo(long msec)
跳转到指定时间点


mesc	long:以毫秒为单位距离开始的偏移时间，其范围应当处于0～振动时长之间，超过振动时长会使播放处于停止状态

异常	
RemoteException	如果与VibratorService的通信发生异常

stop
public void stop ()
停止正在播放的DynamicEffect并清除VibratorService中的状态。应用程序在进行播放结束后需要调用该方法来告知VibratorService，否则系统会认为当前仍然在进行DynamicEffect的播放或处于暂停播放的状态。


RemoteException	如果与VibratorService的通信发生异常







注：带*的方法将于后续版本进行支持
