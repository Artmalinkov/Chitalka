package ru.devimind.chitalka;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Editable metadata is separate from the untouched imported FB2 and reading position. */
final class BookDetailsForm extends LinearLayout {
    private final Design d;
    private final Library library;
    final Library.BookRow book;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable saveJob=()->save();
    private final EditText author,title,country,genre;
    private final TextView status,started,finished;
    final ImageView cover;private final TextView changeCover;
    private boolean dirty;

    BookDetailsForm(Activity activity,Design design,Library library,Library.BookRow book,Runnable back,Runnable read,Runnable chooseCover){
        super(activity);this.d=design;this.library=library;this.book=book;setOrientation(VERTICAL);setId(R.id.book_details);
        LinearLayout header=d.row();header.setPadding(d.dp(4),d.dp(4),d.dp(16),d.dp(4));header.addView(d.icon("back","В библиотеку",()->{if(save())back.run();}),new LayoutParams(d.dp(44),d.dp(48)));header.addView(d.title("О книге",23));addView(header,new LayoutParams(-1,d.dp(64)));addView(d.divider());
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);addView(scroll,new LayoutParams(-1,0,1));LinearLayout fields=d.column();fields.setPadding(d.dp(20),d.dp(20),d.dp(20),d.dp(20));scroll.addView(fields);
        cover=new ImageView(activity);cover.setId(R.id.book_cover);cover.setContentDescription("Обложка книги. Выбрать изображение");cover.setImageResource(R.drawable.ic_launcher);cover.setScaleType(ImageView.ScaleType.FIT_CENTER);cover.setPadding(d.dp(8),d.dp(8),d.dp(8),d.dp(8));cover.setBackground(d.surface(d.field));cover.setOnClickListener(v->chooseCover.run());cover.setFocusable(true);fields.addView(cover,new LayoutParams(-1,d.dp(220)));d.gap(fields,10);
        changeCover=d.button("Изменить обложку",false,chooseCover);changeCover.setId(R.id.change_cover);fields.addView(changeCover,new LayoutParams(-1,d.dp(48)));d.gap(fields,20);
        status=d.text("Сведения сохраняются автоматически",11,d.muted);fields.addView(status);d.gap(fields,16);
        author=field(fields,"Автор",book.author,R.id.book_author);title=field(fields,"Название",book.title,R.id.book_title);
        country=field(fields,"Страна автора",book.country,R.id.book_country);genre=field(fields,"Жанр",book.genre,R.id.book_genre);
        started=dateField(fields,"Дата начала чтения",R.id.book_started,true);finished=dateField(fields,"Дата завершения чтения",R.id.book_finished,false);updateDates();
        TextView help=d.text(book.paper()?"Нажмите «Начать чтение», когда начнёте читать бумажную книгу. Даты можно изменить вручную.":"Дата начала появится при первом открытии текста. Даты можно изменить вручную.",12,d.muted);fields.addView(help);d.gap(fields,16);
        TextView complete=d.button("Завершить чтение",false,()->{if(book.started.isEmpty()){Toast.makeText(activity,"Сначала откройте текст книги или укажите дату начала чтения.",Toast.LENGTH_LONG).show();return;}setDate(false,LocalDate.now().toString());});complete.setId(R.id.finish_reading);fields.addView(complete,new LayoutParams(-1,d.dp(50)));
        LinearLayout footer=d.column();footer.setPadding(d.dp(20),d.dp(12),d.dp(20),d.dp(16));TextView button=d.button(book.paper()?(book.started.isEmpty()?"Начать чтение":"Продолжить чтение"):"Читать",true,()->{if(save())read.run();});button.setId(R.id.read_book);footer.addView(button,new LayoutParams(-1,d.dp(52)));addView(footer);
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence text,int start,int count,int after){}public void onTextChanged(CharSequence text,int start,int before,int count){book.author=author.getText().toString();book.title=title.getText().toString();book.country=country.getText().toString();book.genre=genre.getText().toString();changed();}public void afterTextChanged(Editable text){}};
        for(EditText field:new EditText[]{author,title,country,genre})field.addTextChangedListener(watcher);
        setFocusableInTouchMode(true);requestFocus();
    }
    void coverSaving(boolean saving){changeCover.setText(saving?"Сохраняем обложку…":"Изменить обложку");changeCover.setEnabled(!saving);cover.setEnabled(!saving);}
    private EditText field(LinearLayout into,String label,String value,int id){
        into.addView(d.text(label,12,d.muted));d.gap(into,6);EditText input=d.edit(label,1);input.setId(id);input.setText(value);input.setSingleLine();input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);into.addView(input,new LayoutParams(-1,d.dp(54)));d.gap(into,16);return input;
    }
    private TextView dateField(LinearLayout into,String label,int id,boolean beginning){into.addView(d.text(label,12,d.muted));d.gap(into,6);TextView button=d.button("",false,()->chooseDate(beginning));button.setId(id);into.addView(button,new LayoutParams(-1,d.dp(48)));d.gap(into,16);return button;}
    static String displayDate(String value){return value.isEmpty()?"Не указана":LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy",new Locale("ru")));}
    private void updateDates(){started.setText(displayDate(book.started));started.setContentDescription("Дата начала чтения: "+displayDate(book.started));finished.setText(displayDate(book.finished));finished.setContentDescription("Дата завершения чтения: "+displayDate(book.finished));}
    private void chooseDate(boolean beginning){
        String current=beginning?book.started:book.finished;LocalDate date=current.isEmpty()?LocalDate.now():LocalDate.parse(current);
        Context context=new ContextThemeWrapper(getContext(),d.light?android.R.style.Theme_Material_Light_Dialog_Alert:android.R.style.Theme_Material_Dialog_Alert);
        DatePickerDialog picker=new DatePickerDialog(context,(view,year,month,day)->setDate(beginning,LocalDate.of(year,month+1,day).toString()),date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());
        picker.setTitle(beginning?"Дата начала чтения":"Дата завершения чтения");picker.setButton(DialogInterface.BUTTON_NEUTRAL,"Очистить",(dialog,which)->setDate(beginning,""));picker.show();
    }
    private void setDate(boolean beginning,String value){String start=beginning?value:book.started,end=beginning?book.finished:value;if(!start.isEmpty()&&!end.isEmpty()&&end.compareTo(start)<0){Toast.makeText(getContext(),"Дата завершения не может быть раньше даты начала.",Toast.LENGTH_LONG).show();return;}if(beginning)book.started=value;else book.finished=value;updateDates();changed();save();}
    private void changed(){dirty=true;status.setText("Сохраняется…");handler.removeCallbacks(saveJob);handler.postDelayed(saveJob,350);}
    boolean save(){handler.removeCallbacks(saveJob);if(!dirty)return true;try{library.saveBook(book);dirty=false;status.setText("Сохранено на устройстве");return true;}catch(Exception error){status.setText("Не удалось сохранить. Попробуйте ещё раз.");android.util.Log.e("Chitalka","Book details save failed",error);return false;}}
    @Override protected void onDetachedFromWindow(){save();super.onDetachedFromWindow();}
}
