package poc;

import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.oracle.model.OraMetaModel;
import com.intellij.database.dialects.oracle.model.OraModel;
import com.intellij.database.model.ModelFacade;
import com.intellij.database.model.meta.BasicMetaModel;

/** A separate Tibero model instance with the public schema and DB-link model contracts. */
public final class TiberoModelFacade extends ModelFacade {
    public TiberoModelFacade() { super(GenericDbms.TIBERO); }
    @Override public BasicMetaModel<OraModel> getMetaModel() { return OraMetaModel.MODEL; }
}
