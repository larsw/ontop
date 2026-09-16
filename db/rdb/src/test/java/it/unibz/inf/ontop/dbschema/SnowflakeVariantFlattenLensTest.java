package it.unibz.inf.ontop.dbschema;

import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.model.type.DBTermType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a Snowflake VARIANT column (JSON category) can be flattened by a flatten lens.
 */
public class SnowflakeVariantFlattenLensTest {

    private static final String LENS_FILE = "src/test/resources/snowflake/variant_flatten_lenses.json";
    private static final String DBMETADATA_FILE = "src/test/resources/snowflake/company-variant.db-extract.json";

    private final Lens lens;

    public SnowflakeVariantFlattenLensTest() throws Exception {
        ImmutableSet<Lens> lenses = LensParsingTest.loadViewDefinitionsSnowflake(LENS_FILE, DBMETADATA_FILE);
        assertEquals(1, lenses.size());
        this.lens = lenses.iterator().next();
    }

    /**
     * Flattening a VARIANT produces VARIANT items
     */
    @Test
    public void testOutputColumnType() {
        DBTermType outputType = lens.getAttributes().stream()
                .filter(a -> a.getID().getName().equals("WORKER"))
                .map(Attribute::getTermType)
                .findAny()
                .orElseThrow(() -> new AssertionError("No WORKER attribute in " + lens.getAttributes()));

        assertEquals("VARIANT", outputType.getName());
        assertEquals(DBTermType.Category.JSON, outputType.getCategory());
    }

    /**
     * As a VARIANT is not necessarily an array, the lens must filter out the non-array values
     */
    @Test
    public void testIsArrayFilter() {
        String iqString = lens.getIQ().toString();
        assertTrue(iqString.contains("VARIANT_IS_ARRAY"), iqString);
    }
}
