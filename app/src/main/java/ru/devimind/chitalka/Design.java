package ru.devimind.chitalka;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class Design {
    final Activity a; final boolean light;
    final int bg,panel,field,text,muted,line,silver;
    Design(Activity activity,boolean isLight){a=activity;light=isLight;bg=c(light?"#F4F5F7":"#17191C");panel=c(light?"#FFFFFF":"#22262C");field=c(light?"#ECEFF3":"#1C1F24");text=c(light?"#222831":"#E5E8ED");muted=c(light?"#606B79":"#9FA6B1");line=c(light?"#CED4DE":"#414954");silver=c(light?"#435369":"#C8D2E1");}
    static int c(String hex){return Color.parseColor(hex);}
    int dp(float value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView text(String value,float size,int color){TextView v=new TextView(a);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");return v;}
    TextView title(String value,float size){TextView v=text(value,size,text);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    GradientDrawable surface(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(10));d.setStroke(dp(1),line);return d;}
    android.graphics.drawable.Drawable buttonBackground(boolean primary){
        int[] colors=primary?new int[]{c("#EFF2F7"),c("#B9C4D2"),c("#DCE3ED")}:light?new int[]{c("#FFFFFF"),c("#E9EDF2")}:new int[]{c("#363C45"),c("#242930")};
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors);g.setCornerRadius(dp(9));g.setStroke(dp(1),primary?c("#DCE3ED"):line);
        return new RippleDrawable(ColorStateList.valueOf(c(light?"#30000000":"#30FFFFFF")),g,null);
    }
    TextView button(String label,boolean primary,Runnable action){TextView v=title(label,14);v.setTextColor(primary?c("#17191C"):text);v.setGravity(Gravity.CENTER);v.setPadding(dp(12),dp(8),dp(12),dp(8));v.setMinHeight(dp(48));v.setBackground(buttonBackground(primary));v.setContentDescription(label);v.setOnClickListener(w->action.run());v.setFocusable(true);return v;}
    View icon(String name,String label,Runnable action){Icon v=new Icon(name);v.setContentDescription(label);v.setBackground(new RippleDrawable(ColorStateList.valueOf(c(light?"#18000000":"#20FFFFFF")),null,surface(Color.WHITE)));v.setOnClickListener(w->action.run());v.setFocusable(true);v.setMinimumWidth(dp(44));v.setMinimumHeight(dp(44));return v;}
    EditText edit(String hint,int lines){EditText v=new EditText(a);v.setTextColor(text);v.setHintTextColor(muted);v.setTextSize(16);v.setHint(hint);v.setPadding(dp(12),dp(12),dp(12),dp(12));v.setGravity(Gravity.TOP|Gravity.START);v.setBackground(surface(field));v.setMinLines(lines);v.setSelectAllOnFocus(false);return v;}
    void gap(LinearLayout layout,int height){layout.addView(new View(a),new LinearLayout.LayoutParams(1,dp(height)));}
    View divider(){View v=new View(a);v.setBackgroundColor(line);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return v;}
    private final class Icon extends View {
        final String name; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(String n){super(a);name=n;}
        void line(Canvas canvas,float x,float y,float x2,float y2){canvas.drawLine(x,y,x2,y2,p);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.translate(getWidth()/2f-dp(12),getHeight()/2f-dp(12));canvas.scale(dp(24)/24f,dp(24)/24f);p.setColor(name.equals("trash")?c("#E25454"):silver);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            Path path=new Path();
            switch(name){
                case "back":case "prev":path.moveTo(14,5);path.lineTo(7,12);path.lineTo(14,19);canvas.drawPath(path,p);break;
                case "next":path.moveTo(9,5);path.lineTo(16,12);path.lineTo(9,19);canvas.drawPath(path,p);break;
                case "close":line(canvas,6,6,18,18);line(canvas,6,18,18,6);break;
                case "plus":line(canvas,12,5,12,19);line(canvas,5,12,19,12);break;
                case "gear":
                    for(int i=0;i<32;i++){double angle=-Math.PI/2+i*Math.PI/16;float radius=(i%4==0||i%4==3)?10:7.6f;float x=12+(float)Math.cos(angle)*radius,y=12+(float)Math.sin(angle)*radius;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();canvas.drawPath(path,p);canvas.drawCircle(12,12,3.2f,p);break;
                case "trash":
                    line(canvas,4,7,20,7);line(canvas,9,4,15,4);line(canvas,9,4,8,7);line(canvas,15,4,16,7);path.moveTo(6,7);path.lineTo(7,21);path.lineTo(17,21);path.lineTo(18,7);canvas.drawPath(path,p);line(canvas,10,10,10,18);line(canvas,14,10,14,18);break;
                case "menu":for(int y=6;y<=18;y+=6){p.setStyle(Paint.Style.FILL);canvas.drawCircle(12,y,1.5f,p);}break;
                case "sun":canvas.drawCircle(12,12,4,p);for(int i=0;i<8;i++){double r=i*Math.PI/4;line(canvas,12+(float)Math.cos(r)*7,12+(float)Math.sin(r)*7,12+(float)Math.cos(r)*10,12+(float)Math.sin(r)*10);}break;
                case "notes":canvas.drawRoundRect(5,3,19,21,2,2,p);line(canvas,8,8,16,8);line(canvas,8,12,16,12);line(canvas,8,16,13,16);break;
                default:path.moveTo(3,5);path.cubicTo(6,4,9,5,12,7);path.cubicTo(15,5,18,4,21,5);path.lineTo(21,20);path.cubicTo(18,19,15,19,12,21);path.cubicTo(9,19,6,19,3,20);path.close();canvas.drawPath(path,p);line(canvas,12,7,12,21);
            }
            canvas.restore();
        }
    }
}
