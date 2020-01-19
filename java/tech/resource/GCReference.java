package tech.resource;

import java.lang.ref.*;
import clojure.lang.IFn;
import java.lang.Runnable;
import clojure.lang.IDeref;



public class GCReference
  extends WeakReference<Object>
  implements Runnable, IDeref
{
  IFn disposer;
  public GCReference( Object item, ReferenceQueue<Object> q,
		      IFn _disposer)
  {
    super(item, q);
    disposer = _disposer;
  }
  public void run()
  {
    synchronized(this) {
      if(disposer != null) {
	disposer.invoke(this);
	disposer = null;
      }
    }
  }
  public Object deref() { return get(); }
}
