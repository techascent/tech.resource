package tech.resource;

import java.lang.ref.*;
import java.util.function.Function;
import java.lang.Runnable;



public class GCReference
  extends WeakReference<Object>
  implements Runnable
{
  Function<Object,Object> disposer;
  public GCReference( Object item, ReferenceQueue<Object> q,
		      Function<Object,Object> _disposer)
  {
    super(item, q);
    disposer = _disposer;
  }
  public void run()
  {
    synchronized(this) {
      if(disposer != null) {
	disposer.apply(this);
	disposer = null;
      }
    }
  }
}
