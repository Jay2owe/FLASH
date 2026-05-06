package flash.pipeline.objects;

import ij.IJ;

/**
 * Macro-equivalent helper to configure 3D Objects Counter options.
 * This is required for matching the macro's table schema and redirect behavior.
 */
public final class ObjectsCounterOptions {

    private ObjectsCounterOptions() {}

    /** Macro set_redirect_3DObjectCounter(image_title). */
    public static void setRedirectTo(String imageTitle) {
        String args = "volume surface nb_of_obj._voxels nb_of_surf._voxels integrated_density mean_gray_value "
                + "std_dev_gray_value median_gray_value minimum_gray_value maximum_gray_value centroid "
                + "mean_distance_to_surface std_dev_distance_to_surface median_distance_to_surface centre_of_mass "
                + "bounding_box show_masked_image_(redirection_requiered) dots_size=5 font_size=10 show_numbers "
                + "white_numbers store_results_within_a_table_named_after_the_image_(macro_friendly) "
                + "redirect_to=[" + imageTitle + "]";
        IJ.run("3D OC Options", args);
    }
}
